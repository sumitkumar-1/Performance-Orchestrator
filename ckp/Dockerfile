# Override tool versions and base image with organization-approved versions.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JAVA_RUNTIME_IMAGE}
ARG HELM_VERSION=3.19.0
ARG KUBECTL_VERSION=v1.34.1
USER root
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates tar \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH=$(dpkg --print-architecture) \
    && curl -fsSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-${ARCH}.tar.gz" -o /tmp/helm.tar.gz \
    && curl -fsSL "https://get.helm.sh/helm-v${HELM_VERSION}-linux-${ARCH}.tar.gz.sha256sum" -o /tmp/helm.sha256 \
    && (cd /tmp && echo "$(cut -d ' ' -f1 helm.sha256)  helm.tar.gz" | sha256sum -c -) \
    && tar -xzf /tmp/helm.tar.gz -C /tmp \
    && install -m 0755 /tmp/linux-${ARCH}/helm /usr/local/bin/helm \
    && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" -o /tmp/kubectl \
    && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl.sha256" -o /tmp/kubectl.sha256 \
    && (cd /tmp && echo "$(cat kubectl.sha256)  kubectl" | sha256sum -c -) \
    && install -m 0755 /tmp/kubectl /usr/local/bin/kubectl \
    && rm -rf /tmp/helm* /tmp/linux-* /tmp/kubectl*
WORKDIR /app
COPY target/perf-orchestrator-0.1.0.jar /app/orchestrator.jar
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/orchestrator.jar"]
