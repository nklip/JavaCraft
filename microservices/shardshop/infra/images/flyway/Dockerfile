# Flyway is only an artifact source. Its Temurin runtime never enters the final image.
FROM docker.io/flyway/flyway:13.12.0@sha256:3704e17c99e22adb8385552cdf8dd7fbc05ae6c420cb78215d9ac6cf1261a15c AS distribution
# Keep only the CLI, its core libraries and the PostgreSQL driver; migrations use nothing else.
# SOURCE_DATE_EPOCH does not normalize timestamps copied into the final layer.
RUN mkdir -p /dist/drivers \
    && cp -r /flyway/lib /flyway/licenses /dist/ \
    && rm -rf /dist/lib/aad /dist/lib/netty \
    && find /dist/lib/flyway \( -name 'flyway-database-*' ! -name 'flyway-database-postgresql-*' \
        -o -name 'flyway-firebird-*' -o -name 'flyway-gcp-*' -o -name 'flyway-locations-s3-*' \
        -o -name 'flyway-mysql-*' -o -name 'flyway-singlestore-*' -o -name 'flyway-sqlserver-*' \) -delete \
    && cp /flyway/drivers/postgresql-*.jar /dist/drivers/ \
    && find /dist -exec touch -h -d '@0' {} +

FROM docker.io/ubuntu/jre:25-26.04_stable@sha256:d9de3bc0831ec1f753fe5e94ef5aa9cb3b3ba0388ae85a51328d1c83c06738a3

COPY --from=distribution /dist /flyway
WORKDIR /flyway
USER 10001:10001
ENV HOME=/tmp REDGATE_DISABLE_TELEMETRY=true
# Canonical's runtime is shell-free. Invoke the CLI main class directly.
ENTRYPOINT ["/usr/bin/java", "--enable-native-access=ALL-UNNAMED", "-Djava.net.useSystemProxies=true", "-cp", "/flyway/lib/*:/flyway/lib/flyway/*:/flyway/drivers/*", "org.flywaydb.commandline.Main"]
