// Uses the infrastructure tool's AWS SDK. Credentials must target the private
// backup bucket, independently of public media credentials in .env.
import { S3Client, PutObjectCommand, GetObjectCommand } from '@aws-sdk/client-s3';
import { readFileSync, createReadStream, statSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { createHash } from 'node:crypto';

const manifestPath = resolve(process.argv[2] ?? '');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (basename(manifest.file) !== manifest.file) throw new Error('Invalid archive path');
const archive = resolve(dirname(manifestPath), manifest.file);
const required = [
  'DATA_BACKUP_ENDPOINT',
  'DATA_BACKUP_BUCKET',
  'DATA_BACKUP_ACCESS_KEY_ID',
  'DATA_BACKUP_SECRET_ACCESS_KEY',
];
for (const name of required) if (!process.env[name]) throw new Error(`Missing ${name}`);
if (!/^https:\/\//.test(process.env.DATA_BACKUP_ENDPOINT))
  throw new Error('Backup endpoint requires HTTPS');
const hash = async (stream) => {
  const h = createHash('sha256');
  for await (const chunk of stream) h.update(chunk);
  return h.digest('hex');
};
if ((await hash(createReadStream(archive))) !== manifest.sha256)
  throw new Error('Local archive checksum mismatch');
if (statSync(archive).size > 5_000_000_000)
  throw new Error(
    'Archive exceeds single-upload limit; implement multipart upload before retrying'
  );
const client = new S3Client({
  region: 'auto',
  endpoint: process.env.DATA_BACKUP_ENDPOINT,
  credentials: {
    accessKeyId: process.env.DATA_BACKUP_ACCESS_KEY_ID,
    secretAccessKey: process.env.DATA_BACKUP_SECRET_ACCESS_KEY,
  },
});
const Bucket = process.env.DATA_BACKUP_BUCKET;
const Key = `cartyx-data/${manifest.environment}/${manifest.id}/${manifest.file}`;
try {
  await client.send(
    new PutObjectCommand({
      Bucket,
      Key,
      Body: createReadStream(archive),
      ContentLength: statSync(archive).size,
      ContentType: 'application/gzip',
    })
  );
  const stored = await client.send(new GetObjectCommand({ Bucket, Key }));
  if ((await hash(stored.Body)) !== manifest.sha256)
    throw new Error('Off-host backup checksum mismatch');
  // Completion manifest is published only after a full read-back verification.
  await client.send(
    new PutObjectCommand({
      Bucket,
      Key: `${Key}.json`,
      Body: JSON.stringify(manifest, null, 2),
      ContentType: 'application/json',
    })
  );
  console.log(`Off-host backup verified: ${Key}`);
} finally {
  client.destroy();
}
