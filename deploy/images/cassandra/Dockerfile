# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-11@sha256:c46b31057b9eb90e3ec855f6db35fc99108cadcdd1a03336bfb6516ba12212ef AS dependencies
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM cassandra:4.0.21@sha256:093ee8ee5eb2f714df705b5629d2f82c03687df4dcf30d845ca0f2f594b79d90 AS distribution
# Clean in the build stage so vulnerable JARs/bundled Python ZIPs do not remain
# recoverable in a lower runtime layer. Omit native TLS; use the JRE's JSSE.
RUN rm /opt/cassandra/lib/netty-*.jar /opt/cassandra/lib/jackson-*.jar \
       /opt/cassandra/lib/logback-*.jar /opt/cassandra/lib/snakeyaml-*.jar \
       /opt/cassandra/lib/slf4j-api-*.jar /opt/cassandra/lib/*.zip
# Python 3.12 removed this alias; ConfigParser has been its equivalent since 3.2.
RUN sed -i 's/configparser.SafeConfigParser()/configparser.ConfigParser()/g' /opt/cassandra/pylib/cqlshlib/sslhandling.py \
    && sed -i 's/\.readfp(/.read_file(/g' /opt/cassandra/pylib/cqlshlib/copyutil.py

FROM eclipse-temurin:11.0.32_9-jre-noble@sha256:c55276a409ff48855ce7011fda07da527c41d57cc63b3381533fb7fea3ae027a AS runtime
COPY apt-packages.txt /usr/share/cartyx/apt-packages.txt
RUN apt-get update && xargs apt-get install -y --no-install-recommends < /usr/share/cartyx/apt-packages.txt \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 999 cassandra && useradd --uid 999 --gid 999 --no-create-home --home-dir /var/lib/cassandra cassandra

FROM runtime AS python-dependencies
ADD --checksum=sha256:71138adf1f4ca900cdb7d289c21b7494329f2332b6d85f0e1c42108c0384ed3e https://files.pythonhosted.org/packages/f3/6e/1736e5b4ae2b778ef2f81c47d797de9f891d4d8acb047a24ca37a60294dd/pip-26.2.1-py3-none-any.whl /tmp/pip.whl
COPY requirements.txt /tmp/requirements.txt
RUN PYTHONPATH=/tmp/pip.whl python3 -m pip install --no-cache-dir --require-hashes --only-binary=:all: --target=/opt/cqlsh-python -r /tmp/requirements.txt

FROM runtime
LABEL org.opencontainers.image.source="https://github.com/biozal/cartyx-infrastructure"
LABEL org.opencontainers.image.description="Cartyx Cassandra 4.0.21 with patched runtime dependencies"
ENV CASSANDRA_HOME=/opt/cassandra CASSANDRA_CONF=/etc/cassandra CASSANDRA_VERSION=4.0.21
ENV PATH=/opt/cassandra/bin:$PATH
ENV CQLSH_NO_BUNDLED=1 PYTHONPATH=/opt/cqlsh-python
COPY --from=distribution /opt/cassandra /opt/cassandra
COPY --from=distribution --chown=999:999 /etc/cassandra /etc/cassandra
COPY --from=distribution /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY --from=dependencies /build/target/lib/ /opt/cassandra/lib/
COPY --from=python-dependencies /opt/cqlsh-python /opt/cqlsh-python
RUN mkdir -p /var/lib/cassandra /var/log/cassandra && chown 999:999 /var/lib/cassandra /var/log/cassandra
COPY dependencies.sha256 /usr/share/cartyx/dependencies.sha256
WORKDIR /opt/cassandra
RUN sha256sum -c /usr/share/cartyx/dependencies.sha256 \
    && test "$(find lib -maxdepth 1 -name '*.jar' | wc -l)" = "$(wc -l < /usr/share/cartyx/dependencies.sha256)"
USER 999:999
ENTRYPOINT ["bash", "/config/cassandra-start.sh"]
