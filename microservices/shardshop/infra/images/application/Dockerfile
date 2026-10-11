# One recipe for the six application images. scripts/build-application-images.sh selects
# the target and uses one application's complete target/quarkus-app directory as the context.
# kubectl comes from the digest-pinned official Kubernetes kubectl image.
FROM registry.k8s.io/kubectl:v1.36.5@sha256:0667e3f3e6ef435d7cd4c16684244703ebd67314c17511c2a9f87ea44f6cdf6b AS kubectl

FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:3e921054481a0208daca2194e7982a14063771be914851eae06754a8e6890bab AS application
COPY . /app
# Quarkus also reads config/application.properties in the working directory.
WORKDIR /app
USER 10001:10001
ENV HOME=/tmp

# Ledger and workloads do not generate IDs, thus they start their package directly.
FROM application AS direct
ENTRYPOINT ["/usr/bin/java", "-jar", "/app/quarkus-run.jar"]

# Product and order reserve a fresh generator ID before every JVM start. The runtime
# is shell-free, thus the Java launcher starts and supervises the application JVM.
FROM application AS generator-launcher
COPY --from=kubectl /bin/kubectl /usr/bin/kubectl

FROM generator-launcher AS product
ENTRYPOINT ["/usr/bin/java", "-cp", "/app/lib/main/*", "dev.nklip.javacraft.shardshop.idgen.allocation.GeneratorLauncher", "product", "/app"]

FROM generator-launcher AS order
ENTRYPOINT ["/usr/bin/java", "-cp", "/app/lib/main/*", "dev.nklip.javacraft.shardshop.idgen.allocation.GeneratorLauncher", "order", "/app"]
