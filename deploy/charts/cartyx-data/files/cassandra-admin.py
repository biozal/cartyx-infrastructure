"""CQL bootstrap/readiness using the cqlsh shipped with the pinned Cassandra image.

Passwords go through private files, never arguments, logs or process environment.
The default Cassandra account is usable only inside this isolated data network
during first boot and is disabled as the last provisioning step.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

HOST = os.environ.get('CASSANDRA_HOST', 'cassandra')
GRAPH = os.environ.get('GRAPH_KEYSPACE', 'cartyx_graph')
STATE = os.environ.get('STATE_KEYSPACE', 'cartyx_state')
DC = os.environ.get('CASSANDRA_DC', 'dc1')
for value in [GRAPH, STATE, DC]:
    if not re.fullmatch('[a-z][a-z0-9_]*', value):
        raise ValueError('Invalid keyspace/datacenter identifier')


def secret(name):
    value = Path('/secrets/' + name).read_text().strip()
    if not re.fullmatch('[a-f0-9]{64}', value):
        raise ValueError('Expected generated 256-bit secret: ' + name)
    return value


def query(sql, user='cartyx_admin', password=None, expected=None):
    if password is None:
        password = secret('cassandra-admin-password')
    with tempfile.TemporaryDirectory() as directory:
        config = Path(directory) / 'cqlshrc'
        config.write_text(f'''[authentication]
username = {user}
password = {password}
[ssl]
certfile = /secrets/tls.crt
validate = true
''')
        config.chmod(0o600)
        script = Path(directory) / 'query.cql'
        script.write_text(sql)
        script.chmod(0o600)
        result = subprocess.run(
            ['cqlsh', HOST, '9042', '--ssl', '--cqlshrc', str(config),
             '--connect-timeout', '5', '--request-timeout', '15', '-f', str(script)],
            capture_output=True, text=True, timeout=30,
        )
        # Never echo failed CQL: provisioning statements contain passwords.
        success = result.returncode == 0 and not any(
            marker in result.stderr for marker in ['Error', 'Exception', 'InvalidRequest']
        )
        if expected is not None:
            success = success and expected in result.stdout
        if not success and os.environ.get('CARTYX_CQL_DIAGNOSTICS') == '1':
            print(re.sub(r'[a-f0-9]{64}', '[REDACTED]', result.stderr), file=sys.stderr)
        return success


mode = sys.argv[1] if len(sys.argv) > 1 else 'ready'
if mode == 'ready':
    sys.exit(0 if query(f"SELECT value FROM {STATE}.infrastructure_probe WHERE id = 'bootstrap-v1';", expected='ready') else 1)
if mode == 'security':
    assert query(f'SELECT * FROM {GRAPH}.edgestore LIMIT 1;'), 'Graph table must exist for denial test'
    assert query(f'SELECT * FROM {STATE}.infrastructure_probe;', 'cartyx_state', secret('cassandra-state-password'))
    assert not query(f'SELECT * FROM {GRAPH}.edgestore LIMIT 1;', 'cartyx_state', secret('cassandra-state-password'))
    assert not query(f'SELECT * FROM {STATE}.infrastructure_probe;', 'cartyx_graph', secret('cassandra-graph-password'))
    assert not query('SELECT release_version FROM system.local;', 'cassandra', 'cassandra')
    print('Scoped CQL roles and disabled default login verified')
    sys.exit(0)
if mode != 'bootstrap':
    raise ValueError('Usage: cassandra-admin.py [bootstrap|ready]')

for attempt in range(60):
    try:
        if query('SELECT release_version FROM system.local;'):
            break
        admin = secret('cassandra-admin-password')
        if query(f"CREATE ROLE IF NOT EXISTS cartyx_admin WITH PASSWORD = '{admin}' AND LOGIN = true AND SUPERUSER = true;",
                 'cassandra', 'cassandra'):
            continue  # Wait for role cache propagation and verify the new login.
    except subprocess.TimeoutExpired:
        pass
    time.sleep(5)
else:
    sys.exit('Cassandra bootstrap could not authenticate within the startup budget')

statements = f'''
CREATE KEYSPACE IF NOT EXISTS {GRAPH} WITH replication = {{'class': 'NetworkTopologyStrategy', '{DC}': 1}};
CREATE KEYSPACE IF NOT EXISTS {STATE} WITH replication = {{'class': 'NetworkTopologyStrategy', '{DC}': 1}};
CREATE ROLE IF NOT EXISTS cartyx_graph WITH PASSWORD = '{secret('cassandra-graph-password')}' AND LOGIN = true AND SUPERUSER = false;
CREATE ROLE IF NOT EXISTS cartyx_state WITH PASSWORD = '{secret('cassandra-state-password')}' AND LOGIN = true AND SUPERUSER = false;
GRANT ALL PERMISSIONS ON KEYSPACE {GRAPH} TO cartyx_graph;
GRANT SELECT ON KEYSPACE {STATE} TO cartyx_state;
GRANT MODIFY ON KEYSPACE {STATE} TO cartyx_state;
CREATE TABLE IF NOT EXISTS {STATE}.infrastructure_probe (id text PRIMARY KEY, value text);
ALTER ROLE cassandra WITH LOGIN = false;
INSERT INTO {STATE}.infrastructure_probe (id, value) VALUES ('bootstrap-v1', 'ready');
'''
for attempt in range(12):
    try:
        if query(statements):
            break
    except subprocess.TimeoutExpired:
        pass
    time.sleep(5)
else:
    sys.exit('Cassandra keyspace/role provisioning failed after bounded retries; inspect server logs')
print('Cassandra keyspaces and scoped roles ready; default login disabled')
