import http from 'node:http';
import { X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { clusterApi, paths } from './cluster-api.mjs';

export function metricsFor(environment, state, suspended, expires) {
  paths(environment);
  const timestamp = value => Number.isFinite(Date.parse(value)) ? Date.parse(value) / 1000 : 0;
  const values = {
    cartyx_data_backup_enabled: suspended ? 0 : 1,
    cartyx_data_backup_last_success_timestamp_seconds: timestamp(state.lastSuccess),
    cartyx_data_backup_last_attempt_timestamp_seconds: timestamp(state.lastAttempt),
    cartyx_data_backup_failed: ['failed', 'recovered'].includes(state.phase) ? 1 : 0,
    cartyx_data_backup_in_progress: ['preparing', 'archiving', 'uploading'].includes(state.phase) ? 1 : 0,
    cartyx_data_backup_archive_bytes: Number(state.bytes) || 0,
    cartyx_data_certificate_expiry_timestamp_seconds: timestamp(expires),
  };
  return Object.entries(values).map(([name, value]) => `# TYPE ${name} gauge\n${name}{environment="${environment}"} ${value}\n`).join('');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const api = clusterApi();
  const p = paths(process.env.POD_NAMESPACE);
  http.createServer(async (request, response) => {
    if (request.url === '/healthz') { response.end('ok'); return; }
    if (request.url !== '/metrics') { response.writeHead(404); response.end(); return; }
    try {
      const [status, cron] = await Promise.all([
        api('GET', p.status),
        api('GET', `/apis/batch/v1/namespaces/${p.namespace}/cronjobs/cartyx-data-backup`),
      ]);
      const certificate = new X509Certificate(readFileSync('/certificate/tls.crt'));
      response.writeHead(200, {'content-type': 'text/plain; version=0.0.4'});
      response.end(metricsFor(p.namespace, status.data ?? {}, cron.spec.suspend, certificate.validTo));
    } catch {
      response.writeHead(503); response.end('Backup metrics unavailable\n');
    }
  }).listen(9095, '0.0.0.0');
}
