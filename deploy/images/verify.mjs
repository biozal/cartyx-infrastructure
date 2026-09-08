import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const graph = process.env.CARTYX_JANUSGRAPH_IMAGE;
const cassandra = process.env.CARTYX_CASSANDRA_IMAGE;
assert.ok(graph && cassandra, 'Set both candidate image references explicitly');
const run = (args) => execFileSync('docker', args, {encoding: 'utf8'}).trim();
for (const image of [graph, cassandra]) {
  assert.equal(run(['run', '--rm', '--entrypoint', 'id', image, '-u']), '999');
}
run(['run', '--rm', '--entrypoint', 'java', graph, '-cp', '/opt/janusgraph/lib/*', 'groovy.ui.GroovyMain', '-e', `
assert new com.fasterxml.jackson.core.JsonFactory().version().toString() == '2.22.2'
assert new org.apache.tinkerpop.shaded.jackson.core.JsonFactory().version().toString() == '2.22.2'
assert io.netty.util.Version.identify().values().every { it.artifactVersion() == '4.1.137.Final' }
assert org.apache.tinkerpop.gremlin.util.Gremlin.version() == '3.7.6'
assert !new File('/usr/bin/yq').exists()
for (name in ['org.apache.hadoop.conf.Configuration', 'org.apache.spark.SparkContext', 'org.apache.hadoop.hbase.client.Connection']) {
  try { Class.forName(name); assert false : "Unexpected optional backend: " + name }
  catch (ClassNotFoundException expected) { }
}
println('Patched JSON/Netty and minimal JanusGraph classpath verified')
`]);
run(['run', '--rm', '--entrypoint', 'python3', cassandra, '-c', `
from pathlib import Path
import cassandra, six
assert cassandra.__version__ == '3.30.1'
assert six.__version__ == '1.17.0'
assert not Path('/usr/local/bin/gosu').exists()
assert not list(Path('/opt/cassandra/lib').glob('netty-tcnative*'))
assert not list(Path('/opt/cassandra/lib').glob('*.zip'))
print('Updated CQL tooling and JRE TLS provider verified')
`]);
assert.match(run(['run', '--rm', '--entrypoint', 'cqlsh', cassandra, '--version']), /cqlsh 6\.0\.0/);
console.log('Both candidate images run as UID 999 and pass runtime dependency checks');
