// Rebuilds every mixed (search) index from Cassandra. The Lucene directory is a local
// volume that is deliberately NOT part of the off-host backup: a restored database has
// the authoritative data but an empty search index until this runs.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import gremlin from 'gremlin';

const directory = resolve(process.env.DATA_SECRETS_DIR ?? '.local/data/local');
const endpoint = process.env.GREMLIN_URL ?? 'wss://localhost:18182/gremlin';
const password = readFileSync(`${directory}/gremlin-password`, 'utf8').trim();
const client = new gremlin.driver.Client(endpoint, {
  authenticator: new gremlin.driver.auth.PlainTextSaslAuthenticator('cartyx_admin', password),
  mimeType: 'application/vnd.gremlin-v3.0+json',
  ca: readFileSync(`${directory}/tls.crt`),
  rejectUnauthorized: true,
  traversalSource: 'g',
  pingEnabled: false,
});

const REINDEX_TIMEOUT_MS = 15 * 60 * 1000;

try {
  const timer = setTimeout(() => {
    console.error('Reindex exceeded its deadline');
    process.exit(1);
  }, REINDEX_TIMEOUT_MS);
  try {
    const result = await client.submit(
      `
      import org.janusgraph.core.schema.SchemaAction
      import org.janusgraph.core.schema.SchemaStatus
      import org.janusgraph.graphdb.database.management.ManagementSystem
      def names = []
      def m = graph.openManagement()
      try {
        m.getGraphIndexes(Vertex.class).each { if (it.isMixedIndex()) names.add(it.name()) }
      } finally { m.rollback() }
      names.each { name ->
        ManagementSystem.awaitGraphIndexStatus(graph, name).status(SchemaStatus.ENABLED, SchemaStatus.REGISTERED).call()
        def enable = graph.openManagement()
        try {
          def index = enable.getGraphIndex(name)
          if (index.getFieldKeys().any { index.getIndexStatus(it) == SchemaStatus.REGISTERED })
            enable.updateIndex(index, SchemaAction.ENABLE_INDEX)
          enable.commit()
        } catch (Exception e) { enable.rollback(); throw e }
        ManagementSystem.awaitGraphIndexStatus(graph, name).status(SchemaStatus.ENABLED).call()
        def reindex = graph.openManagement()
        try {
          ManagementSystem.updateIndex(reindex, reindex.getGraphIndex(name), SchemaAction.REINDEX).get()
          reindex.commit()
        } catch (Exception e) { reindex.rollback(); throw e }
      }
      names
    `,
      {}
    );
    const rebuilt = result.toArray().map(String);
    assert.ok(rebuilt.length > 0, 'At least one mixed index must exist to rebuild');
    console.log(`Rebuilt ${rebuilt.length} search index(es): ${rebuilt.join(', ')}`);
  } finally {
    clearTimeout(timer);
  }
} finally {
  await client.close();
}
