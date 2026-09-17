import org.apache.commons.configuration2.BaseConfiguration
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialTraversalSource
import org.yaml.snakeyaml.Yaml

def root = '/tmp/cartyx-graph'
def readSecret = { name -> new File('/secrets/' + name).text.trim() }
def graphConfig = new Properties()
graphConfig.setProperty('gremlin.graph', 'org.janusgraph.core.JanusGraphFactory')
graphConfig.setProperty('storage.backend', 'cql')
graphConfig.setProperty('storage.hostname', System.getenv('CASSANDRA_HOST') ?: 'cassandra')
graphConfig.setProperty('storage.cql.local-datacenter', System.getenv('CASSANDRA_DC') ?: 'dc1')
graphConfig.setProperty('storage.cql.keyspace', System.getenv('GRAPH_KEYSPACE') ?: 'cartyx_graph')
graphConfig.setProperty('storage.username', 'cartyx_graph')
graphConfig.setProperty('storage.password', readSecret('cassandra-graph-password'))
graphConfig.setProperty('storage.cql.read-consistency-level', 'LOCAL_QUORUM')
graphConfig.setProperty('storage.cql.write-consistency-level', 'LOCAL_QUORUM')
graphConfig.setProperty('storage.cql.ssl.enabled', 'true')
graphConfig.setProperty('storage.cql.ssl.hostname_validation', 'true')
graphConfig.setProperty('storage.cql.ssl.truststore.location', '/secrets/tls.p12')
graphConfig.setProperty('storage.cql.ssl.truststore.password', readSecret('tls-password'))
// Embedded mixed index for word search. Valid only while a single JanusGraph instance
// runs (Deployment replicas 1, Recreate). The directory is a separate retained volume;
// after a restore it is rebuilt from Cassandra rather than recovered from a backup.
graphConfig.setProperty('index.search.backend', 'lucene')
graphConfig.setProperty('index.search.directory', System.getenv('JANUSGRAPH_INDEX_DIR') ?: '/var/lib/janusgraph/index')
graphConfig.setProperty('schema.default', 'none')
graphConfig.setProperty('query.force-index', 'true')
graphConfig.setProperty('cache.db-cache', 'false')
new File(root + '/graph.properties').withOutputStream { graphConfig.store(it, null) }

def credentials = new BaseConfiguration()
credentials.setProperty('gremlin.graph', 'org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph')
credentials.setProperty('gremlin.tinkergraph.graphLocation', root + '/credentials.kryo')
credentials.setProperty('gremlin.tinkergraph.graphFormat', 'gryo')
// Credentials are regenerated on each start, so Secret rotation cannot leave an
// old password in a persistent authentication graph.
new File(root + '/credentials.kryo').delete()
def authGraph = TinkerGraph.open(credentials)
authGraph.traversal(CredentialTraversalSource.class).user('cartyx_admin', readSecret('gremlin-password')).iterate()
// Trusted identity-service principal, restricted by IdentityProfileAuthorizer to
// exact immutable-profile bytecode. Never mount the operator password into apps.
def identityPassword = readSecret('gremlin-identity-password')
if (identityPassword.length() < 32 || identityPassword == readSecret('gremlin-password'))
    throw new IllegalStateException('Identity graph credential must be distinct and at least 32 characters')
authGraph.traversal(CredentialTraversalSource.class).user('cartyx_identity', identityPassword).iterate()
authGraph.close()
def authProps = new Properties()
credentials.getKeys().each { k -> authProps.setProperty(k, credentials.getString(k)) }
new File(root + '/credentials.properties').withOutputStream { authProps.store(it, null) }

def yaml = new Yaml()
def config = yaml.load(new File('/opt/janusgraph/conf/janusgraph-server.yaml').text)
config.graphs = [graph: root + '/graph.properties']
config.host = '0.0.0.0'
config.evaluationTimeout = 15000
config.authentication = [authenticator: 'org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator',
    config: [credentialsDb: root + '/credentials.properties']]
config.ssl = [enabled: true, keyStore: '/secrets/tls.p12', keyStoreType: 'PKCS12',
    keyStorePassword: readSecret('tls-password')]
config.metrics = [slf4jReporter: [enabled: true, interval: 60000]]
// The policy, guarded GraphSON decoder and full-request gate are one boundary.
// IdentityChannelizer refuses to start unless all of them are selected together.
config.channelizer = 'io.cartyx.graph.IdentityChannelizer'
config.maxContentLength = 65536
// Close connections with no inbound bytes for 60 s (required and bounded by the channelizer).
config.idleConnectionTimeout = 60000
config.authorization = [authorizer: 'io.cartyx.graph.IdentityProfileAuthorizer', config: [:]]
config.serializers = [[className: 'io.cartyx.graph.IdentityGraphSONSerializer',
    config: [ioRegistries: ['org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry']]]]
new File(root + '/server.yaml').text = yaml.dump(config)
println('JanusGraph TLS/authentication/identity-policy configuration ready')
