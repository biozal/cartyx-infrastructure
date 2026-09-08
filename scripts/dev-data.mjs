import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const action = process.argv[2] ?? 'up';
const run = (command, args) => {
  const result = spawnSync(command, args, { cwd: root, stdio: 'inherit', env: process.env });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
};
// Explicit project name keeps db:up and the full Compose stack on the same
// network/volumes. Never pass --remove-orphans or --volumes here.
const compose = ['compose', '-p', 'cartyx-local', '-f', 'deploy/local/data.compose.yaml'];
switch (action) {
  case 'up':
    run(process.execPath, ['deploy/data/secrets.mjs', 'local']);
    run('docker', [...compose, 'up', '-d', '--wait', '--wait-timeout', '600']);
    break;
  case 'down':
    run('docker', [...compose, 'stop', 'janusgraph', 'cassandra']);
    break;
  case 'status':
    run('docker', [...compose, 'ps', '-a']);
    break;
  case 'smoke':
    run(process.execPath, ['deploy/data/smoke.mjs', process.argv[3] ?? 'verify']);
    break;
  case 'tools':
    run('npm', ['ci', '--prefix', 'deploy/data', '--ignore-scripts']);
    break;
  case 'chart-test':
    run(process.execPath, ['--test', 'deploy/data/tests/chart.test.mjs']);
    break;
  case 'secrets':
    run(process.execPath, ['deploy/data/secrets.mjs', 'local']);
    break;
  case 'kind':
    run('bash', ['deploy/local/data-kind.sh']);
    break;
  default:
    throw new Error('Usage: dev-data.mjs up|down|status|tools|chart-test|secrets|kind|smoke [seed|verify|clean]');
}
