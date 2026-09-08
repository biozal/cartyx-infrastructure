import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import yaml from 'js-yaml';

const chart = 'deploy/charts/cartyx-data';
function render(env, args = []) {
  return yaml
    .loadAll(
      execFileSync(
        'helm',
        [
          'template',
          'cartyx-data',
          chart,
          '--namespace',
          env,
          '-f',
          `${chart}/values-${env}.yaml`,
          ...args,
        ],
        { encoding: 'utf8' }
      )
    )
    .filter(Boolean);
}

for (const environment of ['local', 'dev', 'prod']) {
  test(`${environment}: private services, encrypted authenticated startup, retained data`, () => {
    const resources = render(environment);
    for (const service of resources.filter((r) => r.kind === 'Service')) {
      assert.ok(!service.spec.type || service.spec.type === 'ClusterIP');
      assert.ok(service.spec.ports.every((p) => !p.nodePort));
    }
    const pvc = resources.find((r) => r.kind === 'PersistentVolumeClaim');
    assert.equal(pvc.metadata.annotations['helm.sh/resource-policy'], 'keep');
    assert.equal(pvc.metadata.annotations['kustomize.toolkit.fluxcd.io/prune'], 'disabled');
    assert.equal(
      pvc.spec.storageClassName,
      environment === 'local' ? 'standard' : 'cartyx-data-retain'
    );
    const sts = resources.find((r) => r.kind === 'StatefulSet');
    const cql = resources.find(
      (r) => r.kind === 'Service' && r.metadata.name === 'cartyx-data-cassandra'
    );
    assert.notEqual(cql.spec.clusterIP, 'None', 'CQL clients require a stable Service IP');
    assert.equal(sts.spec.serviceName, 'cartyx-data-cassandra-headless');
    assert.equal(
      sts.spec.template.spec.containers[0].env.find(
        (e) => e.name === 'CASSANDRA_BROADCAST_RPC_ADDRESS'
      ).value,
      cql.metadata.name
    );
    assert.equal(sts.spec.replicas, 1);
    assert.equal(sts.spec.template.spec.automountServiceAccountToken, false);
    assert.equal(
      sts.spec.template.spec.volumes.find((v) => v.name === 'data').persistentVolumeClaim.claimName,
      pvc.metadata.name
    );
    const deployment = resources.find((r) => r.kind === 'Deployment');
    const graphSecrets = deployment.spec.template.spec.volumes.find((v) => v.name === 'secrets')
      .secret.items;
    assert.ok(!graphSecrets.some((s) => s.key === 'cassandra-admin-password'));
    assert.equal(deployment.spec.strategy.type, 'Recreate');
    const policy = resources.find((r) => r.kind === 'NetworkPolicy');
    assert.deepEqual(policy.spec.policyTypes, ['Ingress', 'Egress']);
    assert.ok(
      !policy.spec.ingress.some((r) => r.from.some((f) => f.namespaceSelector || f.ipBlock))
    );
    const config = resources.find((r) => r.kind === 'ConfigMap').data;
    assert.match(config['cassandra-start.sh'], /optional: false/);
    assert.match(config['janusgraph-config.groovy'], /SimpleAuthenticator/);
    assert.match(config['janusgraph-config.groovy'], /enabled: true/);
    for (const name of Object.keys(config)) {
      assert.equal(config[name], readFileSync(`${chart}/files/${name}`, 'utf8'));
    }
    assert.ok(!resources.some((r) => r.kind === 'Secret' || r.kind === 'Ingress'));
  });
}

for (const [setting, reason] of [
  ['cassandra.replicas=3', 'single-node'],
  ['janusgraph.replicas=2', 'single-node'],
  ['graphKeyspace=cartyx_state', 'separate keyspaces'],
  ['graphKeyspace=invalid-keyspace', 'invalid keyspace'],
  ['cassandra.image=cassandra:latest', 'SHA256'],
  ['secretName=', 'secretName'],
]) {
  test(`reject unsafe configuration: ${setting}`, () => {
    const result = spawnSync(
      'helm',
      ['template', 'cartyx-data', chart, '--set', 'environment=dev', '--set', setting],
      { encoding: 'utf8' }
    );
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, new RegExp(reason));
  });
}
