import {execFileSync} from 'node:child_process';
import {readFileSync, appendFileSync} from 'node:fs';
import {resolve} from 'node:path';

const owner = process.env.GITHUB_REPOSITORY_OWNER;
const id = process.env.GITHUB_RUN_ID;
const attempt = process.env.GITHUB_RUN_ATTEMPT;
if (!/^[a-z0-9-]+$/.test(owner ?? '') || !/^\d+$/.test(id ?? '') || !/^\d+$/.test(attempt ?? '')) throw new Error('Invalid release identity');
for (const name of ['cassandra', 'janusgraph']) {
  const repository = `ghcr.io/${owner}/cartyx-${name}`;
  const digests = ['amd64', 'arm64'].map(arch => {
    const record = JSON.parse(readFileSync(resolve(process.argv[2], `data-images-${arch}`, `${name}-digest.json`)));
    if (record.architecture !== arch || record.sourceRevision !== process.env.GITHUB_SHA || !record.digest.startsWith(`${repository}@sha256:`) || !/^[a-z0-9./-]+@sha256:[a-f0-9]{64}$/.test(record.digest)) throw new Error('Mismatched verified artifact');
    return record.digest;
  });
  const tag = `${repository}:build-${id}-${attempt}`;
  execFileSync('docker', ['buildx', 'imagetools', 'create', '--tag', tag, ...digests], {stdio: 'inherit'});
  const manifest = JSON.parse(execFileSync('docker', ['buildx', 'imagetools', 'inspect', tag, '--format', '{{json .Manifest}}'], {encoding: 'utf8'}));
  const index = JSON.parse(execFileSync('docker', ['buildx', 'imagetools', 'inspect', tag, '--raw'], {encoding: 'utf8'}));
  const platforms = index.manifests?.filter(entry => entry.platform?.os === 'linux').map(entry => entry.platform.architecture).sort();
  if (JSON.stringify(platforms) !== JSON.stringify(['amd64', 'arm64'])) throw new Error('Published index must contain both tested Linux architectures');
  if (!/^sha256:[a-f0-9]{64}$/.test(manifest.digest)) throw new Error('Missing final index digest');
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${name}: \`${repository}@${manifest.digest}\` (tag \`${tag}\`)\n\n`);
}
