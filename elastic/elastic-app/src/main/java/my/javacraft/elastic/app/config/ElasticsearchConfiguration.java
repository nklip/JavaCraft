package my.javacraft.elastic.app.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.api.validation.PositiveNumber;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@Configuration
public class ElasticsearchConfiguration {

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    @Value("${spring.elastic.cluster.host:localhost}")
    private String host;
    @Value("${spring.elastic.cluster.port:9200}")
    private String port;
    @Value("${spring.elastic.cluster.user:elastic}")
    private String user;
    @Value("${spring.elastic.cluster.pass:elastic}")
    private String pass;
    @Value("${spring.elastic.cluster.ssl.enabled:true}")
    private String sslEnabled;
    @Value("${spring.elastic.cluster.ssl.path:cert/http_ca.crt}")
    private String sslPath;
    @Value("${spring.elastic.cluster.timeout.connect-ms:3000}")
    private int connectTimeoutMs;
    @Value("${spring.elastic.cluster.timeout.socket-ms:10000}")
    private int socketTimeoutMs;
    @Value("${spring.elastic.cluster.timeout.request-ms:2000}")
    private int requestTimeoutMs;
    @Value("${spring.elastic.cluster.retry.max-attempts:3}")
    private int retryMaxAttempts;
    @Value("${spring.elastic.cluster.retry.initial-backoff-ms:200}")
    private long retryInitialBackoffMs;
    @Value("${spring.elastic.cluster.retry.max-backoff-ms:2000}")
    private long retryMaxBackoffMs;

    /**
     * I load there the same certificate which Kibana uses to connect to elastic search instance.
     */
    public SSLContext getSslContext() throws Exception {
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
        boolean useSsl = isSslEnabled(sslEnabled);
        String resolvedSchema = resolveSchema(useSsl);
        int resolvedConnectTimeout = PositiveNumber.positiveOrDefault(connectTimeoutMs, RestClientBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS);
        int resolvedSocketTimeout = PositiveNumber.positiveOrDefault(socketTimeoutMs, RestClientBuilder.DEFAULT_SOCKET_TIMEOUT_MILLIS);
        int resolvedRequestTimeout = PositiveNumber.positiveOrDefault(requestTimeoutMs, 1000);
        int resolvedRetryAttempts = PositiveNumber.positiveOrDefault(retryMaxAttempts, 1);
        long resolvedInitialBackoff = PositiveNumber.positiveOrDefault(retryInitialBackoffMs, 200L);
        long resolvedMaxBackoff = Math.max(
                resolvedInitialBackoff,
                PositiveNumber.positiveOrDefault(retryMaxBackoffMs, 2_000L)
        );
        String serverUrl = host + ":" + port;
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

        BasicCredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));

        SSLContext sslContext = useSsl ? getSslContext() : null;
        RestClient restClient = RestClient
                .builder(new HttpHost(host, Integer.parseInt(port), resolvedSchema))
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(resolvedConnectTimeout)
                        .setSocketTimeout(resolvedSocketTimeout)
                        .setConnectionRequestTimeout(resolvedRequestTimeout)
                )
                .setHttpClientConfigCallback(hccc -> {
                    hccc.disableAuthCaching()
                            .setDefaultCredentialsProvider(provider);
                    if (sslContext != null) {
                        hccc.setSSLContext(sslContext);
                    }
                    return hccc;
                })
                .build();

        // Create the transport with a Jackson mapper
        ElasticsearchTransport transport = new RetryingElasticsearchTransport(
                new RestClientTransport(restClient, new JacksonJsonpMapper()),
                resolvedRetryAttempts,
                resolvedInitialBackoff,
                resolvedMaxBackoff
        );

        // And create the API client
        return new ElasticsearchClient(transport);
    }

    static boolean isSslEnabled(String sslEnabledValue) {
        return Boolean.parseBoolean(sslEnabledValue == null ? "" : sslEnabledValue.trim());
    }

    static String resolveSchema(boolean useSsl) {
        return useSsl ? HTTPS : HTTP;
    }

    @Bean
    public ObjectMapper getObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
        return objectMapper;
    }

}
