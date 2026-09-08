// Offline, same-version backups. Stops only this task's graph/Cassandra stack.
// No application/CQL clients may write while the backup runs.
import { spawn, execFileSync } from 'node:child_process';
import {
  createReadStream,
  createWriteStream,
  mkdirSync,
  readFileSync,
  writeFileSync,
  rmSync,
} from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname, basename } from 'node:path';

const [action, input] = process.argv.slice(2);
const composeFile = resolve('deploy/local/data.compose.yaml');
const compose = ['compose', '-p', 'cartyx-local', '-f', composeFile];
const image =
  'cassandra:4.0.21@sha256:093ee8ee5eb2f714df705b5629d2f82c03687df4dcf30d845ca0f2f594b79d90';
const run = (args) =>
  execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] }).trim();
const outputDirectory = resolve('.local/backups');
mkdirSync(outputDirectory, { recursive: true, mode: 0o700 });
const lock = `${outputDirectory}/operation.lock`;
mkdirSync(lock); // Fail closed if another backup/restore owns the local lock.

async function hash(path) {
  const h = createHash('sha256');
  for await (const chunk of createReadStream(path)) h.update(chunk);
  return h.digest('hex');
}

try {
  if (action === 'local-backup') {
    const id = `local-${new Date().toISOString().replaceAll(/[:.]/g, '-')}`;
    const destination = `${outputDirectory}/${id}.tar.gz`;
    const container = run([...compose, 'ps', '-q', 'cassandra']);
    if (!container) throw new Error('Local Cassandra must be running for a drained backup');
    const details = JSON.parse(run(['inspect', container]))[0];
    const volume = details.Mounts.find((m) => m.Destination === '/var/lib/cassandra')?.Name;
    if (!volume) throw new Error('Cassandra data must use a named volume');
    if (details.Config.Image !== image)
      throw new Error('Unexpected Cassandra image; update/rehearse backup compatibility first');
    try {
      run([...compose, 'stop', '-t', '60', 'janusgraph']);
      run([...compose, 'exec', '-T', 'cassandra', 'nodetool', 'drain']);
      run([...compose, 'stop', '-t', '180', 'cassandra']);
      const child = spawn(
        'docker',
        [
          'run',
          '--rm',
          '--network',
          'none',
          '--entrypoint',
          'tar',
          '--mount',
          `type=volume,src=${volume},dst=/data,readonly`,
          image,
          '-czf',
          '-',
          '-C',
          '/data',
          '.',
        ],
        { stdio: ['ignore', 'pipe', 'inherit'] }
      );
      const completed = new Promise((res, rej) => {
        child.on('error', rej);
        child.on('exit', (code) =>
          code === 0 ? res() : rej(new Error(`Backup tar exited ${code}`))
        );
      });
      await Promise.all([
        pipeline(child.stdout, createWriteStream(destination, { flags: 'wx', mode: 0o600 })),
        completed,
      ]);
      const manifest = {
        format: 1,
        id,
        createdAt: new Date().toISOString(),
        environment: 'local',
        cassandraImage: image,
        graphKeyspace: 'cartyx_graph',
        stateKeyspace: 'cartyx_state',
        method: 'drained-and-stopped-full-data-directory',
        file: basename(destination),
        sha256: await hash(destination),
        credentials: 'separate recovery copy of .local/data/local is required',
      };
      writeFileSync(`${destination}.json`, JSON.stringify(manifest, null, 2) + '\n', {
        mode: 0o600,
      });
      console.log(`Verified backup archive/checksum: ${destination}`);
    } finally {
      run([...compose, 'up', '-d', '--wait', '--wait-timeout', '600']);
    }
  } else if (action === 'local-restore') {
    if (!input) throw new Error('Pass an archive manifest (.tar.gz.json)');
    const manifestPath = resolve(input);
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (
      manifest.format !== 1 ||
      manifest.cassandraImage !== image ||
      manifest.environment !== 'local'
    ) {
      throw new Error('Only matching-version local archives are supported by this rehearsal');
    }
    if (basename(manifest.file) !== manifest.file) throw new Error('Invalid archive filename');
    const archive = resolve(dirname(manifestPath), manifest.file);
    if ((await hash(archive)) !== manifest.sha256) throw new Error('Backup checksum mismatch');
    const volume = `cartyx-restore-${Date.now()}`;
    run(['volume', 'create', volume]);
    run([
      'run',
      '--rm',
      '--network',
      'none',
      '--entrypoint',
      'tar',
      '--mount',
      `type=volume,src=${volume},dst=/data`,
      '--mount',
      `type=bind,src=${dirname(archive)},dst=/backup,readonly`,
      image,
      '-xzf',
      `/backup/${basename(archive)}`,
      '-C',
      '/data',
    ]);
    // Compose merges ports by target/published port. Use !override to ensure the
    // rehearsal never tries to bind the live local stack's 18182 port.
    const yamlOverride = `${outputDirectory}/restore.compose.yaml`;
    writeFileSync(
      yamlOverride,
      `services:\n  janusgraph:\n    ports: !override\n      - '127.0.0.1:18183:8182'\nvolumes:\n  cassandra-data:\n    external: true\n    name: ${volume}\n`
    );
    run([
      'compose',
      '-p',
      'cartyx-restore',
      '-f',
      composeFile,
      '-f',
      yamlOverride,
      'up',
      '-d',
      '--wait',
      '--wait-timeout',
      '600',
    ]);
    execFileSync(process.execPath, ['deploy/data/smoke.mjs', 'verify'], {
      stdio: 'inherit',
      env: { ...process.env, GREMLIN_URL: 'wss://localhost:18183/gremlin' },
    });
    console.log(
      `Restore verified in independent volume ${volume}. Stop the cartyx-restore project after inspection; source data was untouched.`
    );
  } else {
    throw new Error('Usage: backup.mjs local-backup | local-restore <manifest.tar.gz.json>');
  }
} finally {
  rmSync(lock, { recursive: true });
}
