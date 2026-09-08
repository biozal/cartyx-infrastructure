import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';

for (const [name, variable] of [['cassandra', 'CARTYX_CASSANDRA_IMAGE'], ['janusgraph', 'CARTYX_JANUSGRAPH_IMAGE']]) {
  const image = process.env[variable];
  if (!image?.startsWith('ghcr.io/')) throw new Error('Expected a GHCR candidate');
  const repository = image.split(':')[0];
  const [details] = JSON.parse(execFileSync('docker', ['image', 'inspect', image], {encoding: 'utf8'}));
  const digest = details.RepoDigests.find(d => d.startsWith(`${repository}@sha256:`));
  if (!digest || !/^[a-z0-9./-]+@sha256:[a-f0-9]{64}$/.test(digest)) throw new Error('Missing published digest');
  writeFileSync(resolve(process.argv[2], `${name}-digest.json`), JSON.stringify({image, digest, imageId: details.Id, architecture: details.Architecture, sourceRevision: process.env.GITHUB_SHA}, null, 2));
}
