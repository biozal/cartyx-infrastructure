import { S3Client, GetObjectCommand, PutObjectCommand, ListObjectsV2Command, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { Upload } from '@aws-sdk/lib-storage';
import { createReadStream, readFileSync, writeFileSync, mkdirSync, copyFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { clusterApi, paths, resume, until } from './cluster-api.mjs';

const api = clusterApi();
const p = paths(process.env.POD_NAMESPACE);
const owner = process.env.POD_NAME;
if (!owner) throw new Error('POD_NAME is required');
const secret = key => readFileSync(`/backup-secret/${key}`, 'utf8').trim();
const Bucket = secret('R2_BUCKET');
const s3 = new S3Client({
  region: 'auto', endpoint: `https://${secret('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com`,
  credentials: { accessKeyId: secret('AWS_ACCESS_KEY_ID'), secretAccessKey: secret('AWS_SECRET_ACCESS_KEY') },
});
const hash = async stream => {
  const digest = createHash('sha256');
  for await (const chunk of stream) digest.update(chunk);
  return digest.digest('hex');
};
const prefix = `cartyx-data/${p.namespace}/cluster/`;
const id = new Date().toISOString().replaceAll(/[:.]/g, '-') + '-' + owner;
const Key = `${prefix}${id}.tar.gz`;
const archive = '/work/backup.tar.gz';
let original, locked = false, needsRecovery = false, activeTar;
let cancelled = false;
process.on('SIGTERM', () => { cancelled = true; activeTar?.kill('SIGTERM'); });
process.on('SIGINT', () => { cancelled = true; activeTar?.kill('SIGTERM'); });
const checkpoint = () => { if (cancelled) throw new Error('Backup interrupted; restoring services'); };
const status = data => api('PATCH', p.status, { data });

async function retireOldBackups() {
  // Keep every backup for 14 days and one successful backup per UTC week for
  // 8 weeks. Scope every deletion to this environment's cluster backup prefix.
  const entries = [];
  let ContinuationToken;
  do {
    const page = await s3.send(new ListObjectsV2Command({ Bucket, Prefix: prefix, ContinuationToken }));
    entries.push(...(page.Contents ?? []));
    ContinuationToken = page.IsTruncated ? page.NextContinuationToken : undefined;
  } while (ContinuationToken);
  const manifests = entries.filter(e => e.Key.endsWith('.tar.gz.json')).sort((a, b) => b.LastModified - a.LastModified);
  const weeks = new Set();
  for (const entry of manifests) {
    const age = (Date.now() - entry.LastModified.getTime()) / 86400000;
    const week = Math.floor((entry.LastModified.getTime() / 86400000 + 3) / 7);
    const keep = age <= 14 || (age <= 56 && !weeks.has(week));
    weeks.add(week);
    if (!keep) {
      const key = entry.Key.slice(0, -5);
      if (!key.startsWith(prefix) || !key.endsWith('.tar.gz')) throw new Error('Invalid retention key');
      // Remove completion marker first so a partial delete is never restorable.
      await s3.send(new DeleteObjectCommand({ Bucket, Key: entry.Key }));
      await s3.send(new DeleteObjectCommand({ Bucket, Key: key }));
    }
  }
}

try {
  const lease = await api('GET', p.lease);
  if (process.argv[2] === 'recover') {
    if (!lease.spec?.holderIdentity) {
      console.log('No held backup lease; no recovery needed');
      process.exit(0);
    }
    const pods = await api('GET', `/api/v1/namespaces/${p.namespace}/pods?fieldSelector=${encodeURIComponent(`metadata.name=${lease.spec.holderIdentity}`)}`);
    if (pods.items.some(pod => ['Running', 'Pending'].includes(pod.status.phase))) {
      throw new Error('Original backup pod is still active; refusing concurrent recovery');
    }
    const previous = JSON.parse(lease.metadata.annotations['backup.cartyx.io/original']);
    if (previous.graph !== 1 || previous.cassandra !== 1 || previous.helm !== false || previous.flux !== false) throw new Error('Unexpected recovery state; inspect manually');
    await resume(api, p, previous);
    await api('PATCH', p.lease, { metadata: { resourceVersion: lease.metadata.resourceVersion }, spec: { holderIdentity: null } });
    await status({ phase: 'recovered', error: 'Interrupted backup recovered; run a new backup' });
    console.log('Restored database replicas and Flux reconciliation; released interrupted backup lease');
    process.exit(0);
  }
  if (process.argv[2]) throw new Error('Only the optional recover argument is supported');
  if (lease.spec?.holderIdentity) throw new Error('Backup lease is held; inspect/recover the previous operation before retrying');
  const [graph, cassandra, helm, flux] = await Promise.all([p.graph, p.cassandra, p.helm, p.flux].map(path => api('GET', path)));
  if (graph.spec.replicas !== 1 || cassandra.spec.replicas !== 1 || helm.spec.suspend || flux.spec.suspend) {
    throw new Error('Expected active Flux release and one replica of each database');
  }
  if (helm.status?.conditions?.some(c => c.type === 'Reconciling' && c.status === 'True')) throw new Error('Helm release is reconciling; retry after rollout');
  original = { graph: 1, cassandra: 1, helm: false, flux: false };
  await api('PATCH', p.lease, {
    metadata: { resourceVersion: lease.metadata.resourceVersion, annotations: { 'backup.cartyx.io/original': JSON.stringify(original) } },
    spec: { holderIdentity: owner, acquireTime: new Date().toISOString(), leaseDurationSeconds: 3600 },
  });
  locked = true;
  await status({ phase: 'preparing', lastAttempt: new Date().toISOString(), owner });
  needsRecovery = true;
  await api('PATCH', p.flux, { spec: { suspend: true } });
  await until(async () => {
    const flux = await api('GET', p.flux);
    return !flux.status?.conditions?.some(c => c.type === 'Reconciling' && c.status === 'True');
  }, 'Flux Kustomization idle', 120);
  await api('PATCH', p.helm, { spec: { suspend: true } });
  // Ensure no in-flight reconciliation can race the replica changes.
  await until(async () => {
    const release = await api('GET', p.helm);
    return !release.status?.conditions?.some(c => c.type === 'Reconciling' && c.status === 'True');
  }, 'Helm idle', 120);
  checkpoint();
  await api('PATCH', `${p.graph}/scale`, { spec: { replicas: 0 } });
  await until(async () => (await api('GET', p.pods('janusgraph'))).items.length === 0, 'JanusGraph shutdown');
  checkpoint();
  await api('PATCH', `${p.cassandra}/scale`, { spec: { replicas: 0 } });
  await until(async () => (await api('GET', p.pods('cassandra'))).items.length === 0, 'Cassandra shutdown');
  checkpoint();
  await status({ phase: 'archiving' });
  const recovery = '/work/recovery';
  mkdirSync(`${recovery}/credentials`, { recursive: true, mode: 0o700 });
  for (const name of readdirSync('/database-secret').filter(name => !name.startsWith('.'))) {
    copyFileSync(`/database-secret/${name}`, `${recovery}/credentials/${name}`);
  }
  const config = await api('GET', `/api/v1/namespaces/${p.namespace}/configmaps/cartyx-data-config`);
  const manifest = {
    format: 2, method: 'stopped-full-data-directory', environment: p.namespace, id,
    capturedAt: new Date().toISOString(), cassandraImage: cassandra.spec.template.spec.containers[0].image,
    janusgraphImage: graph.spec.template.spec.containers[0].image,
    chartRevision: helm.status?.lastAppliedRevision ?? helm.status?.history?.[0]?.chartVersion,
    graphKeyspace: graph.spec.template.spec.containers[0].env.find(e => e.name === 'GRAPH_KEYSPACE')?.value,
    stateKeyspace: cassandra.spec.template.spec.containers[0].env.find(e => e.name === 'STATE_KEYSPACE')?.value,
  };
  writeFileSync(`${recovery}/config.json`, JSON.stringify(config.data), { mode: 0o600 });
  writeFileSync(`${recovery}/manifest.json`, JSON.stringify(manifest), { mode: 0o600 });
  await new Promise((resolve, reject) => {
    activeTar = spawn('tar', ['-czf', archive, '-C', '/', 'data', '-C', recovery, 'credentials', 'config.json', 'manifest.json'], { stdio: ['ignore', 'ignore', 'inherit'] });
    activeTar.on('error', reject);
    activeTar.on('exit', code => code === 0 ? resolve() : reject(new Error(`Archive failed: ${code}`)));
  });
  activeTar = undefined;
  checkpoint();
  // Reduce outage: bring the source back before uploading or verifying R2.
  await resume(api, p, original);
  needsRecovery = false;
  await status({ phase: 'uploading' });
  manifest.sha256 = await hash(createReadStream(archive));
  manifest.bytes = statSync(archive).size;
  manifest.key = Key;
  await new Upload({ client: s3, params: { Bucket, Key, Body: createReadStream(archive), ContentType: 'application/gzip' }, queueSize: 1, partSize: 8 * 1024 * 1024, leavePartsOnError: false }).done();
  const readback = await s3.send(new GetObjectCommand({ Bucket, Key }));
  if (await hash(readback.Body) !== manifest.sha256) throw new Error('Off-host read-back checksum mismatch');
  checkpoint();
  await s3.send(new PutObjectCommand({ Bucket, Key: `${Key}.json`, Body: JSON.stringify(manifest), ContentType: 'application/json' }));
  await retireOldBackups();
  await status({ phase: 'completed', lastSuccess: new Date().toISOString(), key: `${Key}.json`, sha256: manifest.sha256, bytes: String(manifest.bytes), error: '' });
  console.log(`Verified off-host backup: ${Key}.json (${manifest.bytes} bytes)`);
} catch (error) {
  if (locked) await status({ phase: 'failed', error: String(error.message).slice(0, 500) }).catch(() => {});
  console.error(error.message);
  process.exitCode = 1;
} finally {
  let recovered = !locked;
  if (locked) {
    try {
      // Also undoes a suspension if interruption happened before scale-down.
      if (needsRecovery) await resume(api, p, original);
      recovered = true;
    } catch (error) {
      console.error(`Recovery incomplete; lease retained for operator recovery: ${error.message}`);
      process.exitCode = 1;
    }
    if (recovered) await api('PATCH', p.lease, { spec: { holderIdentity: null } });
  }
  s3.destroy();
}
