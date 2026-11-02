# The gates image of the AWS deployment. It runs the KindIT gates with the JUnit console
# launcher and kubectl, against the API server of its own cluster. The runbook in
# infra/terraform/README.md stages the context: gates/lib (the test classpath),
# gates/classes (the test classes and features), gates/snapshot (the invocation snapshot of
# the run), gates/source (the source files that the checks read, for example the golden
# routing vectors) and gates/kube/config (a copy of the kubeconfig file of this directory).
FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3
ADD --checksum=sha256:fcc16f63186659d9722014b7e02d5fdd2d4e669cb8c0ab66365d7d3b8ba03509 --chmod=0755 \
    https://dl.k8s.io/release/v1.37.2/bin/linux/arm64/kubectl /usr/local/bin/kubectl
# Only the gates user can read the files. KindLab requires a private mode-700 directory and a
# mode-600 kubeconfig.
COPY --chown=10001:10001 --chmod=u=rwX,go= gates /gates
# The reports of KindIT go below the working directory, on the writable /tmp volume.
WORKDIR /tmp
USER 10001:10001
ENV HOME=/tmp
# The Job gives the tag expression of its gate, for example --include-tag=kind-deployment.
# The system properties are the same as in the Failsafe configuration of the kind profile.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=50", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
    "-Dshardshop.kind.snapshot=/gates/snapshot", "-Dshardshop.directory=/gates/source", \
    "-Dshardshop.kind.cluster=shardshop-test-eks", "-Dshardshop.kind.kubeconfig=/gates/kube/config", \
    "-cp", "/gates/classes:/gates/lib/*", "org.junit.platform.console.ConsoleLauncher", "execute", \
    "--select-class=dev.nklip.javacraft.shardshop.testing.kind.KindIT", "--fail-if-no-tests", "--disable-banner", \
    "--details=tree"]
