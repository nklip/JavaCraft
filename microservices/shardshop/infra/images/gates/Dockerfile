# The gates image of the AWS deployment. It runs the ClusterIT gates and drills with the JUnit
# console launcher and kubectl, against the API server of its own cluster. The runbook in
# infra/terraform/README.md stages the context: gates/lib (the test classpath),
# gates/classes (the test classes and features), gates/snapshot (the invocation snapshot of
# the run), gates/source (the source files that the checks read, for example the golden
# routing vectors) and gates/kube/config (a copy of the kubeconfig file of this directory).

# Flyway is only a tool source here: its shell unpacks the Helm archive, and its runtime never
# enters the final image.
FROM docker.io/flyway/flyway:13.12.0@sha256:3704e17c99e22adb8385552cdf8dd7fbc05ae6c420cb78215d9ac6cf1261a15c AS helm
ADD --checksum=sha256:8cf8f66ecbc252ac8f7f06cca68aba88508ddfde3a6a34feb1678ad1694b4f88 \
    https://get.helm.sh/helm-v4.3.1-linux-arm64.tar.gz /helm.tar.gz
RUN tar -xzf /helm.tar.gz -C / linux-arm64/helm

FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3
ADD --checksum=sha256:fcc16f63186659d9722014b7e02d5fdd2d4e669cb8c0ab66365d7d3b8ba03509 --chmod=0755 \
    https://dl.k8s.io/release/v1.37.2/bin/linux/arm64/kubectl /usr/local/bin/kubectl
# The workload drill renders its temporary seeder Job with helm template.
COPY --from=helm --chmod=0755 /linux-arm64/helm /usr/local/bin/helm
# Only the gates user can read the files. ClusterLab requires a private mode-700 directory and a
# mode-600 kubeconfig.
COPY --chown=10001:10001 --chmod=u=rwX,go= gates /gates
# The reports of ClusterIT and the temporary drill files go to the writable /tmp volume, because
# the snapshot is read-only.
WORKDIR /tmp
USER 10001:10001
ENV HOME=/tmp
# The Job gives the tag expression of its gate or drill, for example --include-tag=cluster-deployment,
# and a drill gives its other system properties in JDK_JAVA_OPTIONS.
# The system properties are the same as in the Failsafe configuration of the kind profile.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=50", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
    "-Dshardshop.cluster.snapshot=/gates/snapshot", "-Dshardshop.cluster.workDirectory=/tmp/work", "-Dshardshop.directory=/gates/source", \
    "-Dshardshop.cluster.name=shardshop-test-eks", "-Dshardshop.cluster.kubeconfig=/gates/kube/config", \
    "-cp", "/gates/classes:/gates/lib/*", "org.junit.platform.console.ConsoleLauncher", "execute", \
    "--select-class=dev.nklip.javacraft.shardshop.testing.cluster.ClusterIT", "--fail-if-no-tests", "--disable-banner", \
    "--details=tree"]
