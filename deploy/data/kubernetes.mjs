import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [action, environment] = process.argv.slice(2);
if (
  !['provision-secret', 'provision-backup-secret', 'cql-security'].includes(action) ||
  !['dev', 'prod', 'local'].includes(environment)
) {
  throw new Error(
    'Usage: node deploy/data/kubernetes.mjs provision-secret|provision-backup-secret|cql-security dev|prod|local'
  );
}
if (!process.env.DATA_KUBECONFIG)
  throw new Error('Set DATA_KUBECONFIG explicitly to the target cluster config');
const namespace = process.env.DATA_NAMESPACE ?? (environment === 'local' ? 'cartyx-local' : environment);
if (process.env.DATA_NAMESPACE && (action !== 'cql-security' || !new RegExp(`^cartyx-restore-${environment}-[0-9]+$`).test(namespace))) {
  throw new Error('DATA_NAMESPACE is only supported for a matching scratch-restore CQL security check');
}
const args = [
  '--kubeconfig',
  process.env.DATA_KUBECONFIG,
  '--request-timeout=10s',
  '-n',
  namespace,
];
const kubectl = (more, input) =>
  execFileSync('kubectl', [...args, ...more], { encoding: 'utf8', input });
kubectl(['get', 'namespace', namespace]);
if (action === 'provision-backup-secret') {
  if (!['dev', 'prod'].includes(environment)) throw new Error('Cluster backup credentials are only for dev/prod');
  if (kubectl(['get', 'secret', 'cartyx-data-backup', '--ignore-not-found', '-o', 'name']).trim()) {
    console.log(`Keeping existing ${namespace}/cartyx-data-backup Secret`);
    process.exit(0);
  }
  const source = JSON.parse(execFileSync('kubectl', [
    '--kubeconfig', process.env.DATA_KUBECONFIG, '--request-timeout=10s',
    '-n', 'platform', 'get', 'secret', 'platform-backup', '-o', 'json',
  ], { encoding: 'utf8' }));
  const data = {};
  for (const key of ['R2_ACCOUNT_ID', 'R2_BUCKET', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']) {
    if (!source.data?.[key]) throw new Error(`Missing backup source key: ${key}`);
    data[key] = source.data[key];
  }
  console.log(kubectl(['create', '-f', '-'], JSON.stringify({
    apiVersion: 'v1', kind: 'Secret', metadata: { name: 'cartyx-data-backup' }, type: 'Opaque', data,
  })).trim());
  process.exit(0);
}
if (action === 'cql-security') {
  const jobs = JSON.parse(
    kubectl(['get', 'jobs', '-l', 'app.kubernetes.io/name=cartyx-data', '-o', 'json'])
  );
  const bootstrap = jobs.items.find((job) =>
    job.metadata.name.startsWith('cartyx-data-bootstrap-')
  );
  if (!bootstrap) throw new Error('Data bootstrap Job not found');
  const name = `cartyx-data-security-${Date.now()}`;
  const pod = bootstrap.spec.template.spec;
  pod.containers[0].command = ['python3', '/config/cassandra-admin.py', 'security'];
  kubectl(
    ['create', '-f', '-'],
    JSON.stringify({
      apiVersion: 'batch/v1',
      kind: 'Job',
      metadata: { name },
      spec: {
        backoffLimit: 0,
        activeDeadlineSeconds: 120,
        template: {
          metadata: { labels: { 'app.kubernetes.io/part-of': 'cartyx-data' } },
          spec: pod,
        },
      },
    })
  );
  try {
    kubectl(['wait', '--for=condition=complete', `job/${name}`, '--timeout=120s']);
    console.log(kubectl(['logs', `job/${name}`]).trim());
  } finally {
    kubectl(['delete', 'job', name]);
  }
  process.exit(0);
}
const existing = kubectl([
  'get',
  'secret',
  'cartyx-data',
  '--ignore-not-found',
  '-o',
  'name',
]).trim();
const directory = resolve(`.local/data/${environment}`);
const appKey = 'gremlin-app-password';
if (existing) {
  const secret = JSON.parse(kubectl(['get', 'secret', 'cartyx-data', '-o', 'json']));
  if (secret.data?.[appKey]) {
    console.log(`Keeping existing ${namespace}/cartyx-data Secret; no rotation performed`);
    process.exit(0);
  }
  // Add only the independent application service credential. Every existing key is
  // left untouched, and a concurrent Secret change fails the precondition.
  if (!existsSync(`${directory}/tls.p12`))
    throw new Error(`Existing Secret needs ${appKey}; restore .local/data/${environment} first`);
  execFileSync(process.execPath, ['deploy/data/secrets.mjs', environment], { stdio: 'inherit' });
  const value = readFileSync(`${directory}/${appKey}`);
  if (value.toString('utf8').trim().length < 32) throw new Error(`Invalid ${appKey}`);
  if (secret.data?.['gremlin-password'] && Buffer.from(secret.data['gremlin-password'], 'base64').equals(value))
    throw new Error(`${appKey} must differ from the operator credential`);
  const scratch = mkdtempSync(resolve('.local/data/.patch-'));
  try {
    const patch = `${scratch}/patch.json`;
    writeFileSync(
      patch,
      JSON.stringify([
        { op: 'test', path: '/metadata/resourceVersion', value: secret.metadata.resourceVersion },
        { op: 'add', path: `/data/${appKey}`, value: value.toString('base64') },
      ]),
      { mode: 0o600, flag: 'wx' }
    );
    kubectl(['patch', 'secret', 'cartyx-data', '--type=json', `--patch-file=${patch}`]);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
  console.log(`Added ${appKey} to ${namespace}/cartyx-data; existing keys unchanged`);
  process.exit(0);
}
execFileSync(process.execPath, ['deploy/data/secrets.mjs', environment], { stdio: 'inherit' });
const files = [
  'tls.p12',
  'tls.crt',
  'tls-password',
  'gremlin-password',
  appKey,
  'cassandra-admin-password',
  'cassandra-graph-password',
  'cassandra-state-password',
];
for (const file of files) {
  if (!existsSync(`${directory}/${file}`) || readFileSync(`${directory}/${file}`).length === 0) {
    throw new Error('Missing or empty credential file: ' + file);
  }
}
console.log(
  kubectl([
    'create',
    'secret',
    'generic',
    'cartyx-data',
    ...files.map((file) => `--from-file=${file}=${directory}/${file}`),
  ]).trim()
);
