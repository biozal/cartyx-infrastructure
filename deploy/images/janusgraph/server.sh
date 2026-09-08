#!/usr/bin/env bash
set -euo pipefail
cd /opt/janusgraph
mapfile -t options < <(sed -n '/^-/p' "${JAVA_OPTIONS_FILE:-/config/jvm.options}")
exec java "${options[@]}" -javaagent:lib/jamm-0.3.3.jar \
  -Dlog4j2.configurationFile=file:/opt/janusgraph/conf/log4j2-server.xml \
  -cp 'conf:lib/*' org.apache.tinkerpop.gremlin.server.GremlinServer "$@"
