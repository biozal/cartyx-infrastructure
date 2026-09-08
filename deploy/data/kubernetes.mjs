import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [action, environment] = process.argv.slice(2);
if (
  !['provision-secret', 'cql-security'].includes(action) ||
  !['dev', 'prod', 'local'].includes(environment)
) {
  throw new Error(
    'Usage: node deploy/data/kubernetes.mjs provision-secret|cql-security dev|prod|local'
  );
}
if (!process.env.DATA_KUBECONFIG)
  throw new Error('Set DATA_KUBECONFIG explicitly to the target cluster config');
const namespace = environment === 'local' ? 'cartyx-local' : environment;
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
if (existing) {
  console.log(`Keeping existing ${namespace}/cartyx-data Secret; no rotation performed`);
  process.exit(0);
}
execFileSync(process.execPath, ['deploy/data/secrets.mjs', environment], { stdio: 'inherit' });
const directory = resolve(`.local/data/${environment}`);
const files = [
  'tls.p12',
  'tls.crt',
  'tls-password',
  'gremlin-password',
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
