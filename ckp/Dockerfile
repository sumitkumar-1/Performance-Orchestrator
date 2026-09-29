# Build first with ./scripts/run-local.sh build. Override with your approved Java 21+ JRE image.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY target/perf-orchestrator-0.1.0.jar /app/orchestrator.jar
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/orchestrator.jar"]
