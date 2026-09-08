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
config.serializers = [[className: 'org.apache.tinkerpop.gremlin.util.ser.GraphSONMessageSerializerV3',
    config: [ioRegistries: ['org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry']]]]
new File(root + '/server.yaml').text = yaml.dump(config)
println('JanusGraph TLS/authentication configuration ready')
