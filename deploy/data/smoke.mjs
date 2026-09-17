import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import gremlin from 'gremlin';

const directory = resolve(process.env.DATA_SECRETS_DIR ?? '.local/data/local');
const endpoint = process.env.GREMLIN_URL ?? 'wss://localhost:18182/gremlin';
const password = readFileSync(`${directory}/gremlin-password`, 'utf8').trim();
const ca = readFileSync(`${directory}/tls.crt`);
const mode = process.argv[2] ?? 'verify';
if (!['seed', 'verify', 'clean'].includes(mode))
  throw new Error('smoke mode must be seed, verify, or clean');
const options = {
  authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
  mimeType: 'application/vnd.gremlin-v3.0+json',
  ca,
  rejectUnauthorized: true,
  traversalSource: 'g',
  pingEnabled: false,
};
const client = new gremlin.driver.Client(endpoint, options);
const request = async (script, bindings = {}) => {
  const timer = setTimeout(() => {
    console.error('Graph request exceeded 45 seconds');
    process.exit(1);
  }, 45000);
  try {
    return await client.submit(script, bindings);
  } finally {
    clearTimeout(timer);
  }
};

try {
  if (mode === 'seed') {
    await request(`
      graph.tx().rollback(); def m = graph.openManagement();
      try {
        if (m.getVertexLabel('InfraProbe') == null) m.makeVertexLabel('InfraProbe').make();
        if (m.getEdgeLabel('INFRA_LINK') == null) m.makeEdgeLabel('INFRA_LINK').make();
        def key = m.getPropertyKey('infraId');
        if (key == null) {
          key = m.makePropertyKey('infraId').dataType(String.class).make();
          m.buildIndex('byInfraId', Vertex.class).addKey(key).unique().buildCompositeIndex();
        }
        if (m.getPropertyKey('infraValue') == null) m.makePropertyKey('infraValue').dataType(String.class).make();
        m.commit();
      } catch (Exception e) { m.rollback(); throw e; }
      true
    `);
    await request(
      `
      def a = g.V().has('infraId', left).fold().coalesce(unfold(), addV('InfraProbe').property('infraId', left)).next();
      def b = g.V().has('infraId', right).fold().coalesce(unfold(), addV('InfraProbe').property('infraId', right)).next();
      a.property('infraValue', 'persisted');
      if (!g.V(a).out('INFRA_LINK').has('infraId', right).hasNext()) a.addEdge('INFRA_LINK', b);
      graph.tx().commit(); true
    `,
      { left: 'cartyx-infra-left', right: 'cartyx-infra-right' }
    );
    console.log('Seeded repeatable infrastructure fixture (no application data)');
  } else if (mode === 'clean') {
    await request(
      "g.V().has('infraId', within(left,right)).drop().iterate(); graph.tx().commit(); true",
      { left: 'cartyx-infra-left', right: 'cartyx-infra-right' }
    );
    console.log('Removed infrastructure fixture');
  } else {
    const result = await request(
      "g.V().has('infraId', left).has('infraValue','persisted').out('INFRA_LINK').has('infraId', right).count().next()",
      { left: 'cartyx-infra-left', right: 'cartyx-infra-right' }
    );
    assert.equal(Number(result.first()), 1, 'Exactly one persisted relationship must survive');
    const remote = new gremlin.driver.DriverRemoteConnection(endpoint, options);
    try {
      const g = gremlin.process.AnonymousTraversalSource.traversal().withRemote(remote);
      const value = await g
        .V()
        .has('infraId', 'cartyx-infra-left')
        .out('INFRA_LINK')
        .count()
        .next();
      assert.equal(Number(value.value), 1, 'JavaScript bytecode traversal must work');
    } finally {
      await remote.close();
    }
    console.log(
      'Verified persisted graph relationship over authenticated TLS/GraphSON 3 and JavaScript bytecode'
    );
  }
} finally {
  await client.close();
}
