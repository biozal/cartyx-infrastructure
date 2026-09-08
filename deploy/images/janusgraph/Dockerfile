# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-11@sha256:c46b31057b9eb90e3ec855f6db35fc99108cadcdd1a03336bfb6516ba12212ef AS dependencies
ADD --checksum=sha256:b969f2640ae2e0ab3a2c25c1b51721e4f12f892521c0619ef807b94e80ca4a71 https://codeload.github.com/apache/tinkerpop/tar.gz/refs/tags/3.7.6 /tmp/tinkerpop.tar.gz
RUN mkdir /source && tar -xzf /tmp/tinkerpop.tar.gz -C /source --strip-components=1 \
    && test "$(grep -c '<version>2.15.2</version>' /source/gremlin-shaded/pom.xml)" = 1 \
    && sed -i 's/<version>2.15.2<\/version>/<version>2.22.2<\/version>/' /source/gremlin-shaded/pom.xml \
    && sed -i '/<artifactId>maven-shade-plugin<\/artifactId>/a\                <version>3.6.2</version>' /source/gremlin-shaded/pom.xml \
    && sed -i '/<\/relocations>/i\                                <relocation><pattern>META-INF/versions/21/com.fasterxml.jackson</pattern><shadedPattern>META-INF/versions/21/org.apache.tinkerpop.shaded.jackson</shadedPattern></relocation>' /source/gremlin-shaded/pom.xml
RUN --mount=type=cache,id=cartyx-java-deps-v1,target=/root/.m2 mvn -B -ntp -f /source/gremlin-shaded/pom.xml -DskipTests -Dproject.build.outputTimestamp=2026-09-08T00:00:00Z package
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,id=cartyx-java-deps-v1,target=/root/.m2 mvn -B -ntp dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib \
    && cp /source/gremlin-shaded/target/gremlin-shaded-3.7.6.jar target/lib/

FROM janusgraph/janusgraph:1.1.0@sha256:75f57aff4b152ca86b4cdaeefb5335154d7fad412cffef75fa44c7504adc96d4 AS upstream
FROM eclipse-temurin:11.0.32_9-jre-noble@sha256:c55276a409ff48855ce7011fda07da527c41d57cc63b3381533fb7fea3ae027a
LABEL org.opencontainers.image.source="https://github.com/biozal/cartyx-infrastructure"
LABEL org.opencontainers.image.description="Cartyx JanusGraph CQL/Gremlin runtime"
RUN groupadd --gid 999 janusgraph && useradd --uid 999 --gid 999 --no-create-home --home-dir /opt/janusgraph janusgraph
WORKDIR /opt/janusgraph
COPY --from=dependencies /build/target/lib/ lib/
COPY --from=upstream /opt/janusgraph/conf/janusgraph-server.yaml /opt/janusgraph/conf/log4j2-server.xml conf/
COPY --from=upstream /opt/janusgraph/scripts/empty-sample.groovy scripts/
COPY --from=upstream /opt/janusgraph/LICENSE.txt /opt/janusgraph/NOTICE.txt ./
COPY --chmod=755 server.sh bin/janusgraph-server.sh
RUN mkdir logs run && chown 999:999 logs run
COPY dependencies.sha256 /usr/share/cartyx/dependencies.sha256
WORKDIR /opt/janusgraph
RUN sha256sum -c /usr/share/cartyx/dependencies.sha256 \
    && test "$(find lib -maxdepth 1 -name '*.jar' | wc -l)" = "$(wc -l < /usr/share/cartyx/dependencies.sha256)"
USER 999:999
ENTRYPOINT ["bash", "/config/janusgraph-start.sh"]
