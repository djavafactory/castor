FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
# Which service the image runs: castor-core (default) or castor-gateway, built by the same Dockerfile
ARG MODULE=castor-core
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY castor-contracts/pom.xml castor-contracts/pom.xml
COPY castor-core/pom.xml castor-core/pom.xml
COPY castor-gateway/pom.xml castor-gateway/pom.xml
# Maven Central now and then answers 404 for a file it has (seen on the wrapper's Maven download): retry before failing
RUN tr -d '' < mvnw > mvnw.lf && mv mvnw.lf mvnw && chmod +x mvnw \
 && for attempt in 1 2 3; do ./mvnw -B -ntp -pl ${MODULE} -am dependency:go-offline && break; \
      [ "$attempt" = 3 ] && exit 1; echo "Maven download failed, retrying in 15s"; sleep 15; done
COPY castor-contracts/src castor-contracts/src
COPY castor-core/src castor-core/src
COPY castor-gateway/src castor-gateway/src
# Build stamps from scripts/build-stamp.sh; unset values keep the pom defaults (<version>-dev+unknown)
ARG BUILD_VERSION=
ARG GIT_COMMIT=
ARG BUILD_TIME=
RUN ./mvnw -B -ntp -pl ${MODULE} -am package -DskipTests \
    ${BUILD_VERSION:+-Dbuild.version=$BUILD_VERSION} ${GIT_COMMIT:+-Dbuild.commit=$GIT_COMMIT} ${BUILD_TIME:+-Dbuild.time=$BUILD_TIME}

FROM eclipse-temurin:25-jre-noble
ARG MODULE=castor-core
ARG API_PORT=8081
ARG MANAGEMENT_PORT=9000
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin castor
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/${MODULE}/target/quarkus-app/ ./
USER 10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    MANAGEMENT_PORT=$MANAGEMENT_PORT
# castor-core: 8081 API, 9000 management; castor-gateway: 8082 and 9002. The management port (health, metrics) is never proxied
EXPOSE ${API_PORT} ${MANAGEMENT_PORT}
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${MANAGEMENT_PORT} && printf 'GET /q/health/ready HTTP/1.0\\r\\n\\r\\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
