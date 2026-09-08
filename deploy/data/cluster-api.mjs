import https from 'node:https';
import { readFileSync } from 'node:fs';

// coordination.k8s.io Lease uses MicroTime, whose decoder requires six digits.
export const microTime = (date = new Date()) => date.toISOString().replace(/Z$/, '000Z');

// Deliberately small Kubernetes client; only the mounted service account is used.
export function clusterApi() {
  const directory = '/var/run/secrets/kubernetes.io/serviceaccount';
  const ca = readFileSync(`${directory}/ca.crt`);
  return (method, path, body) => new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body);
    const request = https.request({
      hostname: process.env.KUBERNETES_SERVICE_HOST,
      port: process.env.KUBERNETES_SERVICE_PORT_HTTPS ?? 443,
      path, method, ca, timeout: 15000,
      headers: {
        authorization: `Bearer ${readFileSync(`${directory}/token`, 'utf8').trim()}`,
        ...(data ? { 'content-type': 'application/merge-patch+json', 'content-length': Buffer.byteLength(data) } : {}),
      },
    }, response => {
      let text = '';
      response.on('data', chunk => { text += chunk; });
      response.on('end', () => {
        if (response.statusCode < 200 || response.statusCode >= 300) {
          // Never include API response bodies, which could contain credentials.
          reject(new Error(`Kubernetes ${method} ${path}: HTTP ${response.statusCode}`));
        } else {
          try { resolve(JSON.parse(text)); } catch (error) { reject(error); }
        }
      });
      response.on('error', reject);
    });
    request.on('timeout', () => request.destroy(new Error('Kubernetes request timed out')));
    request.on('error', reject);
    request.end(data);
  });
}

export async function until(check, label, seconds = 600) {
  const deadline = Date.now() + seconds * 1000;
  do {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 3000));
  } while (Date.now() < deadline);
  throw new Error(`Timed out waiting for ${label}`);
}

export function paths(namespace) {
  if (!['dev', 'prod'].includes(namespace)) throw new Error('Backup namespace must be dev or prod');
  return {
    namespace,
    lease: `/apis/coordination.k8s.io/v1/namespaces/${namespace}/leases/cartyx-data-backup`,
    status: `/api/v1/namespaces/${namespace}/configmaps/cartyx-data-backup-status`,
    helm: `/apis/helm.toolkit.fluxcd.io/v2/namespaces/${namespace}/helmreleases/cartyx-data`,
    flux: `/apis/kustomize.toolkit.fluxcd.io/v1/namespaces/flux-system/kustomizations/data-${namespace}`,
    graph: `/apis/apps/v1/namespaces/${namespace}/deployments/cartyx-data-janusgraph`,
    cassandra: `/apis/apps/v1/namespaces/${namespace}/statefulsets/cartyx-data-cassandra`,
    pods: component => `/api/v1/namespaces/${namespace}/pods?labelSelector=${encodeURIComponent(`app.kubernetes.io/instance=cartyx-data,app.kubernetes.io/component=${component}`)}`,
  };
}

export async function resume(api, p, original) {
  // Restore Cassandra before JanusGraph. Keep Flux suspended until both recover.
  await api('PATCH', `${p.cassandra}/scale`, { spec: { replicas: original.cassandra } });
  await api('PATCH', `${p.graph}/scale`, { spec: { replicas: original.graph } });
  await until(async () => {
    const cassandra = await api('GET', p.cassandra);
    const graph = await api('GET', p.graph);
    return (cassandra.status.readyReplicas ?? 0) === original.cassandra &&
      (graph.status.availableReplicas ?? 0) === original.graph;
  }, 'database recovery');
  await api('PATCH', p.helm, { spec: { suspend: original.helm } });
  await api('PATCH', p.flux, { spec: { suspend: original.flux } });
}
