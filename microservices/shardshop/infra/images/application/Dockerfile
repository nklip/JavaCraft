# One recipe for the six application images. scripts/up.sh uses one
# application's complete target/quarkus-app directory as the context.
FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3
COPY . /app
# Quarkus also reads config/application.properties in the working directory.
WORKDIR /app
USER 10001:10001
ENV HOME=/tmp
# Product and order reserve a fresh generator ID in this JVM at every start, through the
# Kubernetes API with their service account token. The image contains no kubectl.
ENTRYPOINT ["/usr/bin/java", "-jar", "/app/quarkus-run.jar"]
