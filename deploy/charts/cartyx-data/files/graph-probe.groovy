import org.apache.tinkerpop.gremlin.driver.Cluster
import org.apache.tinkerpop.gremlin.util.ser.GraphSONMessageSerializerV3

def cluster = Cluster.build('localhost').port(8182)
    .enableSsl(true).trustStore('/secrets/tls.p12').trustStoreType('PKCS12')
    .trustStorePassword(new File('/secrets/tls-password').text.trim())
    .credentials('cartyx_admin', new File('/secrets/gremlin-password').text.trim())
    .serializer(new GraphSONMessageSerializerV3()).create()
try {
    // Probe the active backend session: schema metadata can be served from a
    // cache even after Cassandra disappears. No application schema is needed.
    cluster.connect().submit('graph.getBackend().getStoreManager().getSession().execute("SELECT release_version FROM system.local").one().getString("release_version")').all().get()
} finally {
    cluster.close()
}
