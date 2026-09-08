import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

if (!process.env.DATA_KUBECONFIG) throw new Error('Set DATA_KUBECONFIG explicitly');
const label = process.argv[2];
if (!['kind', 'z440'].includes(label)) throw new Error('Label the cluster: kind|z440');
const get = (args) =>
  JSON.parse(
    execFileSync(
      'kubectl',
      ['--kubeconfig', process.env.DATA_KUBECONFIG, '--request-timeout=10s', ...args],
      { encoding: 'utf8' }
    )
  );
const nodes = get(['get', 'nodes', '-o', 'json']);
const pods = get(['get', 'pods', '-A', '-o', 'json']);
const storageClasses = get(['get', 'storageclasses', '-o', 'json']);
const filesystems = [];
for (const node of nodes.items) {
  const stats = get(['get', '--raw', `/api/v1/nodes/${node.metadata.name}/proxy/stats/summary`]);
  filesystems.push({
    node: node.metadata.name,
    root: stats.node.fs,
    image: stats.node.runtime?.imageFs,
  });
}
const report = {
  capturedAt: new Date().toISOString(),
  label,
  nodes: nodes.items.map((n) => ({
    name: n.metadata.name,
    capacity: n.status.capacity,
    allocatable: n.status.allocatable,
    system: n.status.nodeInfo,
  })),
  filesystems,
  storageClasses: storageClasses.items.map((s) => ({
    name: s.metadata.name,
    provisioner: s.provisioner,
    reclaimPolicy: s.reclaimPolicy,
    binding: s.volumeBindingMode,
  })),
  workloads: pods.items.map((p) => ({
    namespace: p.metadata.namespace,
    name: p.metadata.name,
    phase: p.status.phase,
    resources: p.spec.containers.map((c) => ({ name: c.name, resources: c.resources })),
  })),
};
mkdirSync('.local/data/preflight', { recursive: true, mode: 0o700 });
writeFileSync(`.local/data/preflight/${label}.json`, JSON.stringify(report, null, 2) + '\n', {
  mode: 0o600,
});
console.log(`Read-only capacity/storage report: .local/data/preflight/${label}.json`);
