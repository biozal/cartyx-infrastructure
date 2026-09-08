import { randomBytes } from 'node:crypto';
import { mkdirSync, existsSync, writeFileSync, chmodSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { execFileSync } from 'node:child_process';

const environment = process.argv[2] ?? 'local';
if (!['local', 'dev', 'prod', 'restore'].includes(environment)) {
  throw new Error('Environment must be local, dev, prod, or restore');
}
const parent = resolve('.local/data');
const directory = join(parent, environment);
mkdirSync(parent, { recursive: true, mode: 0o700 });
chmodSync(parent, 0o700);
if (existsSync(join(directory, 'tls.p12'))) {
  console.log(`Reusing ${environment} data credentials; no rotation performed`);
  process.exit(0);
}
if (existsSync(directory))
  throw new Error(`Incomplete credentials at ${directory}; inspect before retrying`);
mkdirSync(directory, { mode: 0o755 });
for (const key of [
  'cassandra-admin-password',
  'cassandra-graph-password',
  'cassandra-state-password',
  'gremlin-password',
  'tls-password',
]) {
  // The containing parent is owner-only on the host; uid 999 containers need to
  // read the mounted files. Kubernetes uses a Secret volume with fsGroup 999.
  writeFileSync(join(directory, key), randomBytes(32).toString('hex') + '\n', { mode: 0o644 });
}
const hosts = [
  'localhost',
  'cassandra',
  'janusgraph',
  'cartyx-data-cassandra',
  'cartyx-data-janusgraph',
];
for (const namespace of [environment, 'cartyx-local']) {
  for (const service of ['cartyx-data-cassandra', 'cartyx-data-janusgraph']) {
    hosts.push(`${service}.${namespace}.svc`, `${service}.${namespace}.svc.cluster.local`);
  }
}
const config = `[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=cartyx-data-${environment}\n[extensions]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\nsubjectAltName=${hosts.map((h) => `DNS:${h}`).join(',')},IP:127.0.0.1\n`;
writeFileSync(join(directory, 'openssl.cnf'), config);
execFileSync(
  'openssl',
  [
    'req',
    '-x509',
    '-newkey',
    'rsa:3072',
    '-nodes',
    '-days',
    '365',
    '-config',
    join(directory, 'openssl.cnf'),
    '-keyout',
    join(directory, 'tls.key'),
    '-out',
    join(directory, 'tls.crt'),
  ],
  { stdio: 'ignore' }
);
execFileSync(
  'openssl',
  [
    'pkcs12',
    '-export',
    '-name',
    'cartyx-data',
    '-inkey',
    join(directory, 'tls.key'),
    '-in',
    join(directory, 'tls.crt'),
    '-out',
    join(directory, 'tls.p12'),
    '-passout',
    `file:${join(directory, 'tls-password')}`,
  ],
  { stdio: 'ignore' }
);
chmodSync(join(directory, 'tls.p12'), 0o644);
console.log(
  `Created ${environment} data credentials and a 365-day TLS certificate in .local/data/${environment}`
);
