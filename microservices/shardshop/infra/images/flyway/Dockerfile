# Flyway is only an artifact source. Its Temurin runtime never enters the final image.
FROM docker.io/flyway/flyway:13.8.1@sha256:6e00e7503a6fd48b12650fc6748088a356009f2aefb60736dc147518629263af AS distribution
# Keep only the CLI, its core libraries and the PostgreSQL driver; migrations use nothing else.
RUN mkdir -p /dist/drivers \
    && cp -r /flyway/lib /flyway/licenses /dist/ \
    && rm -rf /dist/lib/aad /dist/lib/netty \
    && find /dist/lib/flyway \( -name 'flyway-database-*' ! -name 'flyway-database-postgresql-*' \
        -o -name 'flyway-firebird-*' -o -name 'flyway-gcp-*' -o -name 'flyway-locations-s3-*' \
        -o -name 'flyway-mysql-*' -o -name 'flyway-singlestore-*' -o -name 'flyway-sqlserver-*' \) -delete \
    && cp /flyway/drivers/postgresql-*.jar /dist/drivers/

FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:3e921054481a0208daca2194e7982a14063771be914851eae06754a8e6890bab

COPY --from=distribution /dist /flyway
WORKDIR /flyway
USER 10001:10001
ENV HOME=/tmp REDGATE_DISABLE_TELEMETRY=true
# Canonical's runtime is shell-free. Invoke the CLI main class directly.
ENTRYPOINT ["/usr/bin/java", "--enable-native-access=ALL-UNNAMED", "-Djava.net.useSystemProxies=true", "-cp", "/flyway/lib/*:/flyway/lib/flyway/*:/flyway/drivers/*", "org.flywaydb.commandline.Main"]
