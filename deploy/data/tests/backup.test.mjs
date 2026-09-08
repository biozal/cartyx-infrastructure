import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import yaml from 'js-yaml';
import { paths, resume } from '../cluster-api.mjs';

const original = { graph: 1, cassandra: 1, helm: false, flux: false };
test('recovery waits for databases before restoring Flux, with Cassandra first', async () => {
  const p = paths('dev');
  const calls = [];
  await resume(async (method, path, body) => {
    calls.push({method, path, body});
    return {status: {readyReplicas: 1, availableReplicas: 1}};
  }, p, original);
  assert.deepEqual(calls.map(c => [c.method, c.path]), [
    ['PATCH', `${p.cassandra}/scale`], ['PATCH', `${p.graph}/scale`],
    ['GET', p.cassandra], ['GET', p.graph], ['PATCH', p.helm], ['PATCH', p.flux],
  ]);
});
test('failed database recovery leaves Flux suspended for operator recovery', async () => {
  const p = paths('prod');
  const calls = [];
  await assert.rejects(resume(async (method, path) => {
    calls.push(path);
    if (method === 'GET') throw new Error('backend unavailable');
    return {};
  }, p, original), /backend unavailable/);
  assert.ok(!calls.includes(p.helm));
  assert.ok(!calls.includes(p.flux));
});
test('backup cannot target arbitrary application namespaces', () => {
  assert.throws(() => paths('platform'), /dev or prod/);
});
for (const environment of ['dev', 'prod']) {
  test(`${environment}: backup credentials isolated from dependency installation and scoped permissions`, () => {
    const docs = yaml.loadAll(execFileSync('kubectl', ['kustomize', `data/${environment}`], {encoding: 'utf8'}));
    const cron = docs.find(r => r.kind === 'CronJob');
    assert.equal(cron.metadata.namespace, environment);
    assert.equal(cron.spec.concurrencyPolicy, 'Forbid');
    assert.equal(cron.spec.jobTemplate.spec.backoffLimit, 0);
    const pod = cron.spec.jobTemplate.spec.template.spec;
    assert.equal(pod.automountServiceAccountToken, false);
    const secretVolumes = pod.volumes.filter(v => v.secret || v.projected).map(v => v.name);
    for (const container of pod.initContainers) {
      assert.ok(container.volumeMounts.every(v => !secretVolumes.includes(v.name)));
      assert.ok(!container.volumeMounts.some(v => v.name === 'data'));
      assert.match(container.command.join(' '), /--ignore-scripts/);
    }
    assert.equal(pod.containers[0].volumeMounts.find(v => v.name === 'data').readOnly, true);
    const role = docs.find(r => r.kind === 'Role');
    assert.ok(!role.rules.some(r => r.resources.includes('secrets') || r.resources.includes('*')));
    for (const rule of role.rules.filter(r => r.verbs.includes('patch'))) assert.ok(rule.resourceNames.length);
    const code = docs.find(r => r.kind === 'ConfigMap' && r.metadata.name.startsWith('cartyx-data-backup-code-'));
    for (const name of ['package.json', 'package-lock.json', 'backup-cluster.mjs', 'cluster-api.mjs']) {
      assert.equal(code.data[name], readFileSync(`deploy/data/${name}`, 'utf8'));
    }
  });
}
