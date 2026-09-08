import { execFileSync, spawn } from 'node:child_process';
const target = process.argv[2];
if (!['dev', 'prod'].includes(target) || !process.env.DATA_KUBECONFIG) throw new Error('Set DATA_KUBECONFIG and pass dev|prod');
const other = target === 'dev' ? 'prod' : 'dev';
const base = ['--kubeconfig', process.env.DATA_KUBECONFIG, '--request-timeout=10s'];
const image = 'cassandra:4.0.21@sha256:093ee8ee5eb2f714df705b5629d2f82c03687df4dcf30d845ca0f2f594b79d90';
const cases = [
  { name: 'data', namespace: target, labels: { 'app.kubernetes.io/part-of': 'cartyx-data' }, expected: [true, true] },
  { name: 'client', namespace: target, labels: { 'cartyx.io/data-client': 'true' }, expected: [true, false] },
  { name: 'unlabeled', namespace: target, labels: {}, expected: [false, false] },
  { name: 'cross-namespace', namespace: other, labels: { 'app.kubernetes.io/part-of': 'cartyx-data', 'cartyx.io/data-client': 'true' }, expected: [false, false] },
];
const wait = (namespace, name) => new Promise((resolve, reject) => {
  const child = spawn('kubectl', [...base, '-n', namespace, 'wait', '--for=condition=complete', `job/${name}`, '--timeout=120s'], { stdio: 'ignore' });
  child.on('error', reject);
  child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${namespace}/${name} failed or timed out`)));
});
const jobs = [];
try {
  for (const entry of cases) {
    const name = `cartyx-data-network-${Date.now()}-${entry.name}`;
    const script = `import socket\nchecks=[('cartyx-data-janusgraph.${target}.svc.cluster.local',8182,${entry.expected[0] ? 'True' : 'False'}),('cartyx-data-cassandra.${target}.svc.cluster.local',9042,${entry.expected[1] ? 'True' : 'False'})]\nfor host,port,expected in checks:\n socket.gethostbyname(host)\n try:\n  connection=socket.create_connection((host,port),timeout=4); connection.close(); allowed=True\n except (TimeoutError,ConnectionRefusedError,OSError):\n  allowed=False\n assert allowed==expected, f'{host}:{port}: allowed={allowed}, expected={expected}'\n print(f'{host}:{port}: {"allowed" if allowed else "denied"}')\n`;
    const manifest = { apiVersion: 'batch/v1', kind: 'Job', metadata: { name, namespace: entry.namespace }, spec: {
      backoffLimit: 0, activeDeadlineSeconds: 110, template: { metadata: { labels: entry.labels }, spec: {
        restartPolicy: 'Never', automountServiceAccountToken: false, securityContext: { runAsUser: 999, runAsGroup: 999, seccompProfile: { type: 'RuntimeDefault' } },
        containers: [{ name: 'probe', image, command: ['python3', '-u', '-c', script], resources: { requests: { cpu: '10m', memory: '32Mi' }, limits: { cpu: '100m', memory: '64Mi' } }, securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ['ALL'] } } }],
      } } } };
    execFileSync('kubectl', [...base, 'create', '-f', '-'], { input: JSON.stringify(manifest), stdio: ['pipe', 'ignore', 'inherit'] });
    jobs.push({ name, namespace: entry.namespace });
  }
  const results = await Promise.allSettled(jobs.map(async ({ name, namespace }) => {
    await wait(namespace, name);
    console.log(execFileSync('kubectl', [...base, '-n', namespace, 'logs', `job/${name}`], { encoding: 'utf8' }).trim());
  }));
  for (const result of results) if (result.status === 'rejected') throw result.reason;
  console.log(`Verified live ${target} NetworkPolicy: allowed paths work; unlabelled and cross-namespace paths are denied.`);
} finally {
  for (const {name, namespace} of jobs) execFileSync('kubectl', [...base, '-n', namespace, 'delete', 'job', name, '--wait=false'], { stdio: 'ignore' });
}
