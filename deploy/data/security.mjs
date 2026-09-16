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

// The identity-service principal must authenticate, then receive only policy
// denials for anything other than its exact immutable-profile bytecode. Allowed
// profile forms need the application's identity schema and are tested there.
const identity = readFileSync(`${dir}/gremlin-identity-password`, 'utf8').trim();
assert.ok(identity.length >= 32 && identity !== password, 'Distinct identity graph credential');
const bounded = async (name, action) => {
  const timer = setTimeout(() => {
    console.error(`${name}: no bounded result`);
    process.exit(1);
  }, 20000);
  try {
    await action();
    console.log(`Verified ${name}`);
  } finally {
    clearTimeout(timer);
  }
};
const restricted = {
  ...base,
  authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_identity', identity),
};
const denied = /Request denied/;
await bounded('identity principal script denial', async () => {
  const client = new gremlin.driver.Client(endpoint, restricted);
  try {
    await assert.rejects(() => client.submit('1+1'), denied);
  } finally {
    await client.close();
  }
});
await bounded('identity principal general traversal denial', async () => {
  const remote = new gremlin.driver.DriverRemoteConnection(endpoint, restricted);
  try {
    const g = gremlin.process.AnonymousTraversalSource.traversal().withRemote(remote);
    await assert.rejects(() => g.V().limit(1).count().next(), denied);
    await assert.rejects(() => g.addV('InfraProbe').property('infraId', 'denied').next(), denied);
  } finally {
    await remote.close();
  }
});
await bounded('operator access through the identity policy', async () => {
  const client = new gremlin.driver.Client(endpoint, {
    ...base,
    authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
  });
  try {
    assert.equal(Number((await client.submit('1+1')).first()), 2);
  } finally {
    await client.close();
  }
});
// permessage-deflate is never negotiated: upstream inflation is unbounded before authentication.
await bounded('compression not negotiated', async () => {
  const client = new gremlin.driver.Client(endpoint, {
    ...base,
    enableCompression: true,
    authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
  });
  try {
    assert.equal(Number((await client.submit('1+1')).first()), 2);
    assert.equal(client._connection._ws.extensions, '', 'Server must not accept permessage-deflate');
  } finally {
    await client.close();
  }
});
// Checks that make the server close a connection stay last: kubectl port-forward (used by
// restore-cluster.mjs) terminates every forwarded connection when that happens.
// GraphBinary must be closed before TinkerPop's default binary decoder runs.
await bounded('unsupported binary serializer rejection', async () => {
  const client = new gremlin.driver.Client(endpoint, {
    ...base,
    mimeType: 'application/vnd.graphbinary-v1.0',
    authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
  });
  try {
    await assert.rejects(() => client.submit('1+1'), /Connection has been closed/);
  } finally {
    await client.close().catch(() => {});
  }
});
