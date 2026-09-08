#!/usr/bin/env bash
set -euo pipefail
# Patch the upstream configuration without placing secrets in environment variables.
python3 - <<'PY'
from pathlib import Path
import re
p = Path('/etc/cassandra/cassandra.yaml')
s = p.read_text()
s = re.sub(r'^authenticator:.*$', 'authenticator: PasswordAuthenticator', s, flags=re.M)
s = re.sub(r'^authorizer:.*$', 'authorizer: CassandraAuthorizer', s, flags=re.M)
password = Path('/secrets/tls-password').read_text().strip()
assert re.fullmatch('[a-f0-9]{64}', password), 'Expected generated TLS password'
tls = f'''client_encryption_options:
    enabled: true
    optional: false
    keystore: /secrets/tls.p12
    keystore_password: {password}
    store_type: PKCS12
    require_client_auth: false
'''
s = re.sub(r'^client_encryption_options:.*?(?=^[a-z][a-z_]*:)', tls, s, flags=re.M | re.S)
p.write_text(s)
PY
exec /usr/local/bin/docker-entrypoint.sh cassandra -f
