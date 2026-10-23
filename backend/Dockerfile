FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY castor-core/pom.xml castor-core/pom.xml
RUN tr -d '' < mvnw > mvnw.lf && mv mvnw.lf mvnw && chmod +x mvnw && ./mvnw -B -ntp -pl castor-core -am dependency:go-offline
COPY castor-core/src castor-core/src
# Build stamps from scripts/build-stamp.sh; unset values keep the pom defaults (<version>-dev+unknown)
ARG BUILD_VERSION=
ARG GIT_COMMIT=
ARG BUILD_TIME=
RUN ./mvnw -B -ntp -pl castor-core -am package -DskipTests \
    ${BUILD_VERSION:+-Dbuild.version=$BUILD_VERSION} ${GIT_COMMIT:+-Dbuild.commit=$GIT_COMMIT} ${BUILD_TIME:+-Dbuild.time=$BUILD_TIME}

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin castor
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/castor-core/target/quarkus-app/ ./
USER 10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 8081 API, 9000 management (health, metrics): never proxied
EXPOSE 8081 9000
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9000 && printf 'GET /q/health/ready HTTP/1.0\\r\\n\\r\\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
