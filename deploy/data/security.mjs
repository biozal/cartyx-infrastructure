import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import gremlin from 'gremlin';

const dir = resolve(process.env.DATA_SECRETS_DIR ?? '.local/data/local');
const endpoint = process.env.GREMLIN_URL ?? 'wss://localhost:18182/gremlin';
const password = readFileSync(`${dir}/gremlin-password`, 'utf8').trim();
const base = {
  mimeType: 'application/vnd.gremlin-v3.0+json',
  ca: readFileSync(`${dir}/tls.crt`),
  rejectUnauthorized: true,
  pingEnabled: false,
};
for (const [name, options, pattern] of [
  [
    'incorrect password',
    {
      ...base,
      authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator(
        'cartyx_admin',
        'incorrect'
      ),
    },
    /401|authentication|Username and\/or password/i,
  ],
  ['missing credentials', base, /authentication|authenticator|credentials|401|407/i],
  [
    'untrusted certificate',
    {
      ...base,
      ca: undefined,
      authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
    },
    /self.signed|certificate|unable to verify/i,
  ],
]) {
  const timer = setTimeout(() => {
    console.error(`${name}: no bounded rejection`);
    process.exit(1);
  }, 20000);
  const client = new gremlin.driver.Client(endpoint, options);
  try {
    await assert.rejects(() => client.submit('1+1'), pattern);
    console.log(`Rejected ${name}`);
  } finally {
    clearTimeout(timer);
    await client.close();
  }
}
