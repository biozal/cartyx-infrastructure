// Disaster-recovery rehearsal: reads only R2 artifacts, never the source PVC or
// database Secret. Requires a new namespace and creates an independent volume.
import { S3Client, GetObjectCommand } from '@aws-sdk/client-s3';
import { createReadStream, createWriteStream, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import yaml from 'js-yaml';

const [environment, manifestKey] = process.argv.slice(2);
if (!['dev', 'prod'].includes(environment) || !process.env.DATA_KUBECONFIG) throw new Error('Set DATA_KUBECONFIG; usage: restore-cluster.mjs dev|prod <R2 manifest key>');
if (!manifestKey?.startsWith(`cartyx-data/${environment}/cluster/`) || !manifestKey.endsWith('.tar.gz.json')) throw new Error('Manifest must be in the selected environment cluster-backup prefix');
const namespace = `cartyx-restore-${environment}-${Date.now()}`;
const directory = resolve(`.local/restores/${namespace}`);
mkdirSync(directory, {recursive: true, mode: 0o700});
const archive = `${directory}/backup.tar.gz`;
const base = ['--kubeconfig', process.env.DATA_KUBECONFIG, '--request-timeout=10s'];
const kubectl = (args, input) => execFileSync('kubectl', [...base, ...args], {input, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024});
const externalKeys = ['DATA_BACKUP_ENDPOINT', 'DATA_BACKUP_BUCKET', 'DATA_BACKUP_ACCESS_KEY_ID', 'DATA_BACKUP_SECRET_ACCESS_KEY'];
let connection;
if (externalKeys.some(key => process.env[key])) {
  if (!externalKeys.every(key => process.env[key])) throw new Error('Provide all four DATA_BACKUP_* settings');
  connection = {endpoint: process.env.DATA_BACKUP_ENDPOINT, bucket: process.env.DATA_BACKUP_BUCKET, accessKeyId: process.env.DATA_BACKUP_ACCESS_KEY_ID, secretAccessKey: process.env.DATA_BACKUP_SECRET_ACCESS_KEY};
} else {
  const secret = JSON.parse(kubectl(['-n', environment, 'get', 'secret', 'cartyx-data-backup', '-o', 'json']));
  const value = key => Buffer.from(secret.data[key], 'base64').toString('utf8').trim();
  connection = {endpoint: `https://${value('R2_ACCOUNT_ID')}.r2.cloudflarestorage.com`, bucket: value('R2_BUCKET'), accessKeyId: value('AWS_ACCESS_KEY_ID'), secretAccessKey: value('AWS_SECRET_ACCESS_KEY')};
}
if (!connection.endpoint.startsWith('https://')) throw new Error('Backup endpoint requires HTTPS');
const s3 = new S3Client({region: 'auto', endpoint: connection.endpoint, credentials: {accessKeyId: connection.accessKeyId, secretAccessKey: connection.secretAccessKey}});
const startedAt = Date.now();
let forward;
try {
  const response = await s3.send(new GetObjectCommand({Bucket: connection.bucket, Key: manifestKey}));
  if (response.ContentLength > 1024 * 1024) throw new Error('Manifest exceeds 1 MiB');
  const manifest = JSON.parse(await response.Body.transformToString());
  if (manifest.format !== 2 || manifest.environment !== environment || manifest.key !== manifestKey.slice(0, -5) || !/^[a-f0-9]{64}$/.test(manifest.sha256)) throw new Error('Invalid completion manifest');
  const chart = 'deploy/charts/cartyx-data';
  const defaults = yaml.load(readFileSync(`${chart}/values.yaml`, 'utf8'));
  const values = yaml.load(readFileSync(`${chart}/values-${environment}.yaml`, 'utf8'));
  if (manifest.cassandraImage !== defaults.cassandra.image || manifest.janusgraphImage !== defaults.janusgraph.image) throw new Error('Check out the matching database image/chart revision before restoring');
  const object = await s3.send(new GetObjectCommand({Bucket: connection.bucket, Key: manifest.key}));
  await pipeline(object.Body, createWriteStream(archive, {flags: 'wx', mode: 0o600}));
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(archive)) hash.update(chunk);
  if (hash.digest('hex') !== manifest.sha256) throw new Error('Downloaded archive checksum mismatch');
  // Extract only exact metadata member names to memory; never unpack arbitrary
  // archive paths onto the operator workstation.
  const member = name => execFileSync('tar', ['-xOf', archive, name], {maxBuffer: 8 * 1024 * 1024});
  const inner = JSON.parse(member('manifest.json'));
  if (inner.id !== manifest.id || inner.environment !== environment || inner.cassandraImage !== manifest.cassandraImage || inner.janusgraphImage !== manifest.janusgraphImage) throw new Error('Archive metadata does not match its completion manifest');
  const config = JSON.parse(member('config.json'));
  for (const [name, content] of Object.entries(config)) {
    if (!/^[a-zA-Z0-9_.-]+$/.test(name) || readFileSync(`${chart}/files/${name}`, 'utf8') !== content) throw new Error('Check out the matching chart configuration before restoring');
  }
  const keys = ['tls.p12', 'tls.crt', 'tls-password', 'gremlin-password', 'cassandra-admin-password', 'cassandra-graph-password', 'cassandra-state-password'];
  const credentials = `${directory}/credentials`;
  mkdirSync(credentials, {mode: 0o700});
  for (const key of keys) writeFileSync(`${credentials}/${key}`, member(`credentials/${key}`), {mode: 0o600, flag: 'wx'});
  // Creation (not apply/upsert) intentionally fails if any destination exists.
  kubectl(['create', 'namespace', namespace]);
  kubectl(['-n', namespace, 'create', 'secret', 'generic', 'cartyx-data', ...keys.map(key => `--from-file=${key}=${credentials}/${key}`)]);
  const pvc = {apiVersion: 'v1', kind: 'PersistentVolumeClaim', metadata: {name: 'cartyx-data-cassandra', namespace, labels: {'app.kubernetes.io/managed-by': 'Helm'}, annotations: {'meta.helm.sh/release-name': 'cartyx-data', 'meta.helm.sh/release-namespace': namespace, 'helm.sh/resource-policy': 'keep', 'kustomize.toolkit.fluxcd.io/prune': 'disabled'}}, spec: {accessModes: ['ReadWriteOnce'], storageClassName: 'cartyx-data-retain', resources: {requests: {storage: values.cassandra.storage}}}};
  kubectl(['create', '-f', '-'], JSON.stringify(pvc));
  const job = {apiVersion: 'batch/v1', kind: 'Job', metadata: {name: 'restore-loader', namespace}, spec: {backoffLimit: 0, activeDeadlineSeconds: 3600, template: {metadata: {labels: {app: 'restore-loader'}}, spec: {restartPolicy: 'Never', automountServiceAccountToken: false, securityContext: {runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: 'RuntimeDefault'}}, containers: [{name: 'loader', image: manifest.cassandraImage, command: ['bash', '-c', 'test -z "$(ls -A /restore-data)" && touch /tmp/empty-verified && exec sleep 3600'], readinessProbe: {exec: {command: ['test', '-f', '/tmp/empty-verified']}, periodSeconds: 2}, resources: {requests: {cpu: '100m', memory: '64Mi'}, limits: {cpu: '1', memory: '256Mi'}}, securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ['ALL']}}, volumeMounts: [{name: 'data', mountPath: '/restore-data'}]}], volumes: [{name: 'data', persistentVolumeClaim: {claimName: 'cartyx-data-cassandra'}}]}}}};
  kubectl(['create', '-f', '-'], JSON.stringify(job));
  kubectl(['-n', namespace, 'wait', '--for=condition=Ready', 'pod', '-l', 'app=restore-loader', '--timeout=120s']);
  const extract = spawn('kubectl', [...base, '-n', namespace, 'exec', '-i', 'job/restore-loader', '--', 'tar', '-xzf', '-', '-C', '/restore-data', '--strip-components=1', '--no-same-owner', '--no-same-permissions', 'data'], {stdio: ['pipe', 'ignore', 'inherit']});
  const finished = new Promise((resolve, reject) => {extract.on('error', reject); extract.on('exit', code => code === 0 ? resolve() : reject(new Error(`Volume extraction failed: ${code}`)));});
  await Promise.all([pipeline(createReadStream(archive), extract.stdin), finished]);
  kubectl(['-n', namespace, 'delete', 'job', 'restore-loader', '--wait=true', '--timeout=60s']);
  execFileSync('helm', ['--kubeconfig', process.env.DATA_KUBECONFIG, 'upgrade', '--install', 'cartyx-data', chart, '-n', namespace, '-f', `${chart}/values-${environment}.yaml`, '--wait', '--wait-for-jobs', '--timeout', '15m'], {stdio: 'inherit'});
  forward = spawn('kubectl', [...base, '-n', namespace, 'port-forward', 'svc/cartyx-data-janusgraph', ':8182', '--address=127.0.0.1'], {stdio: ['ignore', 'pipe', 'pipe']});
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Restore port-forward timed out')), 30000);
    forward.once('error', reject);
    forward.once('exit', () => reject(new Error('Restore port-forward exited')));
    forward.stdout.on('data', chunk => {const match = String(chunk).match(/127\.0\.0\.1:(\d+) ->/); if (match) {clearTimeout(timer); resolve(match[1]);}});
  });
  const env = {...process.env, DATA_SECRETS_DIR: credentials, GREMLIN_URL: `wss://localhost:${port}/gremlin`};
  execFileSync(process.execPath, ['deploy/data/smoke.mjs', 'verify'], {stdio: 'inherit', env});
  execFileSync(process.execPath, ['deploy/data/security.mjs'], {stdio: 'inherit', env});
  execFileSync(process.execPath, ['deploy/data/kubernetes.mjs', 'cql-security', environment], {stdio: 'inherit', env: {...process.env, DATA_NAMESPACE: namespace}});
  const claim = JSON.parse(kubectl(['-n', namespace, 'get', 'pvc', 'cartyx-data-cassandra', '-o', 'json']));
  const result = {verifiedAt: new Date().toISOString(), environment, namespace, volume: claim.spec.volumeName, manifestKey, sha256: manifest.sha256, durationSeconds: Math.round((Date.now()-startedAt)/1000), sourceCapturedAt: manifest.capturedAt};
  writeFileSync(`${directory}/result.json`, JSON.stringify(result, null, 2)+'\n', {mode: 0o600});
  console.log(JSON.stringify(result, null, 2));
  console.log('Fresh-volume off-host restore verified. Scratch namespace/PV remain for inspection; remove only these rehearsal resources after recording evidence.');
} finally {
  forward?.kill('SIGTERM');
  s3.destroy();
}
