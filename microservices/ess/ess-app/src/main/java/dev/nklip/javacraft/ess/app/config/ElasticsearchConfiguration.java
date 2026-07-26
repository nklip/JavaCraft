package dev.nklip.javacraft.ess.app.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.ess.api.validation.PositiveNumber;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfiguration {

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    private final ElasticsearchProperties properties;

    /**
     * I load there the same certificate which Kibana uses to connect to elastic search instance.
     */
    SSLContext getSslContext(String sslPath) throws Exception {
        Certificate trustedCa;
        ClassPathResource trustResource = new ClassPathResource(sslPath);
        try (InputStream is = trustResource.getInputStream()) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            trustedCa = factory.generateCertificate(is);
        }
        KeyStore trustStore = KeyStore.getInstance("pkcs12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", trustedCa);
        return SSLContexts
                .custom()
                .loadTrustMaterial(trustStore, null)
                .build();
    }

    @Bean
    public ElasticsearchClient getElasticsearchClient() throws Exception {
        boolean useSsl = properties.ssl().enabled();
        String resolvedSchema = resolveSchema(useSsl);
        int resolvedConnectTimeout = PositiveNumber.positiveOrDefault(
                properties.timeout().connectMs(),
                Rest5ClientBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS
        );
        int resolvedSocketTimeout = PositiveNumber.positiveOrDefault(
                properties.timeout().socketMs(),
                Rest5ClientBuilder.DEFAULT_SOCKET_TIMEOUT_MILLIS
        );
        int resolvedRequestTimeout = PositiveNumber.positiveOrDefault(properties.timeout().requestMs(), 1000);
        int resolvedRetryAttempts = PositiveNumber.positiveOrDefault(properties.retry().maxAttempts(), 1);
        long resolvedInitialBackoff = PositiveNumber.positiveOrDefault(properties.retry().initialBackoffMs(), 200L);
        long resolvedMaxBackoff = Math.max(
                resolvedInitialBackoff,
                PositiveNumber.positiveOrDefault(properties.retry().maxBackoffMs(), 2_000L)
        );
        String serverUrl = properties.host() + ":" + properties.port();
        log.info(
                "Creating rest client for elasticsearch cluster (url='{}', schema='{}', ssl.enabled='{}', connectTimeoutMs='{}', socketTimeoutMs='{}', requestTimeoutMs='{}', retryAttempts='{}', retryInitialBackoffMs='{}', retryMaxBackoffMs='{}')...",
                serverUrl,
                resolvedSchema,
                useSsl,
                resolvedConnectTimeout,
                resolvedSocketTimeout,
                resolvedRequestTimeout,
                resolvedRetryAttempts,
                resolvedInitialBackoff,
                resolvedMaxBackoff
        );

        HttpHost httpHost = new HttpHost(resolvedSchema, properties.host(), properties.port());

        BasicCredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(
                new AuthScope(httpHost),
                new UsernamePasswordCredentials(properties.user(), properties.pass().toCharArray())
        );

        // The Elasticsearch 9 low-level client is built on Apache HttpClient 5, which configures
        // connect/socket timeouts on the connection manager and TLS through a TlsStrategy,
        // rather than through the callbacks the HttpClient 4 based RestClient used.
        PoolingAsyncClientConnectionManagerBuilder connectionManagerBuilder =
                PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(resolvedConnectTimeout))
                                .setSocketTimeout(Timeout.ofMilliseconds(resolvedSocketTimeout))
                                .build());

        SSLContext sslContext = useSsl ? getSslContext(properties.ssl().path()) : null;
        if (sslContext != null) {
            connectionManagerBuilder.setTlsStrategy(
                    ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildAsync()
            );
        }

        CloseableHttpAsyncClient httpClient = HttpAsyncClients.custom()
                .setConnectionManager(connectionManagerBuilder.build())
                .setDefaultCredentialsProvider(provider)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(resolvedRequestTimeout))
                        .build())
                .disableAuthCaching()
                .build();

        Rest5Client restClient = Rest5Client.builder(httpHost)
                .setHttpClient(httpClient)
                .build();

        // Create the transport with a Jackson mapper
        ElasticsearchTransport transport = new RetryingElasticsearchTransport(
                new Rest5ClientTransport(restClient, new Jackson3JsonpMapper()),
                resolvedRetryAttempts,
                resolvedInitialBackoff,
                resolvedMaxBackoff
        );

        // And create the API client
        return new ElasticsearchClient(transport);
    }

    static String resolveSchema(boolean useSsl) {
        return useSsl ? HTTPS : HTTP;
    }

    /**
     * Declared as {@link JsonMapper} rather than {@code ObjectMapper} on purpose: Spring
     * Boot 4 auto-configures a {@code JsonMapper} bean behind {@code @ConditionalOnMissingBean},
     * so a bean typed as the {@code ObjectMapper} supertype does not suppress it and the
     * HTTP message converters would silently use Boot's mapper instead of this one. That
     * matters here because the Elasticsearch response types expose {@code acknowledged()}
     * style accessors rather than JavaBean getters, so they only serialize under the
     * field visibility configured below.
     */
    @Bean
    public JsonMapper getObjectMapper() {
        // Jackson 3 mappers are immutable, so configuration moves into the builder.
        // The previous code called setDefaultPropertyInclusion twice; NON_EMPTY was the
        // one that survived, so that is the behaviour preserved here.
        return JsonMapper.builder()
                .changeDefaultVisibility(visibility -> visibility.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
                .changeDefaultPropertyInclusion(_ -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY))
                .build();
    }

}
