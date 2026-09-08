#!/usr/bin/env bash
set -euo pipefail
umask 077
mkdir -p /tmp/cartyx-graph
cd /opt/janusgraph
# The preparation script creates a credentials store from the mounted Secret and
# configures TLS. It never logs the resulting files or passwords.
java -Xms64m -Xmx256m -cp 'lib/*' groovy.ui.GroovyMain /config/janusgraph-config.groovy
export JAVA_OPTIONS_FILE=/config/jvm.options
exec bin/janusgraph-server.sh /tmp/cartyx-graph/server.yaml
