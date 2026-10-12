package dev.nklip.javacraft.shardshop.idgen.allocation;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads and advances the registry ConfigMap through the Kubernetes API with Apache HttpClient 5, as a service account.
 * The token file is read again for each request, because Kubernetes rotates projected tokens. A scheduled cancel ends
 * each request at its timeout, which includes the connection, the TLS handshake and the response.
 */
public final class KubernetesRegistry implements GeneratorRegistry, AutoCloseable {
    /** The ConfigMap has one data key; with its managed fields, a response stays far below this limit. */
    static final int MAX_BODY_BYTES = 65_536;
    private static final String REGISTRY = "shardshop-snowflake-generators";
    private static final String FIELD_MANAGER = "shardshop-generator-allocator";
    private static final ContentType JSON_PATCH = ContentType.create("application/json-patch+json");
    private static final Timeout MAX_REQUEST = Timeout.ofSeconds(5);
    private static final Pattern HIGH_WATER = Pattern.compile("0|[1-9][0-9]{0,3}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    /** A bearer token of RFC 6750; a service account token is a JWT. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final URI registry;
    private final URI registryPatch;
    private final Path tokenFile;
    private final CloseableHttpClient http;
    private final ScheduledThreadPoolExecutor deadlines;

    KubernetesRegistry(URI apiServer, String namespace, Path tokenFile, TlsSocketStrategy tls)
            throws AllocationFailure {
        String path = apiServer.getRawPath();
        if (apiServer.getHost() == null || apiServer.getRawUserInfo() != null
                || !(path.isEmpty() || "/".equals(path))
                || apiServer.getRawQuery() != null || apiServer.getRawFragment() != null
                || namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new AllocationFailure(AllocationFailure.Reason.API_CLIENT);
        }
        registry = URI.create(apiServer.getScheme() + "://" + apiServer.getRawAuthority() + "/api/v1/namespaces/"
                + namespace + "/configmaps/" + REGISTRY);
        registryPatch = URI.create(registry + "?fieldManager=" + FIELD_MANAGER);
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile");
        http = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(tls)
                        .setMaxConnTotal(1)
                        .setMaxConnPerRoute(1)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(MAX_REQUEST)
                                .setSocketTimeout(MAX_REQUEST)
                                .build())
                        .build())
                .setUserAgent(FIELD_MANAGER)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build();
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("generator-registry-deadline").daemon().factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /**
     * Connects to an https API server URL with only a host and an optional port. The client trusts only the CA
     * certificates of caFile, and the host name must match the server certificate.
     */
    public static KubernetesRegistry connect(String apiServer, String namespace, String tokenFile, String caFile)
            throws AllocationFailure {
        try {
            URI server = new URI(apiServer);
            if (!"https".equals(server.getScheme())) {
                throw new AllocationFailure(AllocationFailure.Reason.API_CLIENT);
            }
            KeyStore trusted = trustStore(Path.of(caFile));
            return new KubernetesRegistry(server, namespace, Path.of(tokenFile),
                    new DefaultClientTlsStrategy(SSLContexts.custom().loadTrustMaterial(trusted, null).build()));
        } catch (AllocationFailure failure) {
            throw failure;
        } catch (URISyntaxException | IOException | GeneralSecurityException | IllegalArgumentException invalid) {
            throw new AllocationFailure(AllocationFailure.Reason.API_CLIENT);
        }
    }

    @Override
    public RegistryState read(Duration timeout) throws AllocationFailure {
        try {
            HttpGet get = new HttpGet(registry);
            authorize(get);
            Response response = send(get, timeout);
            if (response.status() != HttpStatus.SC_OK) {
                throw new IOException("Generator registry is unavailable");
            }
            return parse(response.body());
        } catch (IOException failure) {
            throw new AllocationFailure(AllocationFailure.Reason.REGISTRY_READ);
        }
    }

    @Override
    public boolean reserve(RegistryState expected, int next, Duration timeout) throws AllocationFailure {
        Objects.requireNonNull(expected, "expected");
        if (next <= expected.highWaterMark() || next > 1023) {
            throw new IllegalArgumentException("Reservation must advance the live generator high-water mark");
        }
        HttpPatch patch = new HttpPatch(registryPatch);
        try {
            authorize(patch);
            patch.setEntity(new ByteArrayEntity(patch(expected, next), JSON_PATCH));
        } catch (IOException notSent) {
            throw new AllocationFailure(AllocationFailure.Reason.REGISTRY_WRITE);
        }
        Response response;
        try {
            response = send(patch, timeout);
        } catch (IOException unknownOutcome) {
            // The API server can commit the patch before the connection fails; the caller burns this slot.
            return false;
        }
        return switch (response.status()) {
            case HttpStatus.SC_OK -> reserved(response.body(), expected, next);
            case HttpStatus.SC_UNAUTHORIZED, HttpStatus.SC_FORBIDDEN, HttpStatus.SC_NOT_FOUND ->
                    throw new AllocationFailure(AllocationFailure.Reason.REGISTRY_WRITE);
            // 409 and 422: a failed test operation or a policy denial. 429 and 5xx: an unknown outcome.
            default -> false;
        };
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
        http.close(CloseMode.IMMEDIATE);
    }

    private void authorize(HttpUriRequestBase request) throws IOException {
        String token = Files.readString(tokenFile, StandardCharsets.US_ASCII).strip();
        if (!TOKEN.matcher(token).matches()) {
            throw new IOException("The service account token is invalid");
        }
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
    }

    private Response send(HttpUriRequestBase request, Duration timeout) throws IOException {
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("Kubernetes API timeout must be positive");
        }
        Timeout limit = Timeout.of(timeout);
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(limit)
                .setResponseTimeout(limit)
                .build());
        ScheduledFuture<?> deadline = deadlines.schedule(request::cancel, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            return http.execute(request, response -> new Response(response.getCode(), body(response)));
        } finally {
            deadline.cancel(false);
        }
    }

    private static byte[] body(ClassicHttpResponse response) throws IOException {
        byte[] body = Objects.requireNonNullElse(
                EntityUtils.toByteArray(response.getEntity(), MAX_BODY_BYTES + 1), new byte[0]);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("Kubernetes API response exceeds the limit");
        }
        return body;
    }

    private static RegistryState parse(byte[] body) throws IOException {
        JsonNode configMap = JSON.readTree(body);
        if (!(configMap instanceof ObjectNode)) {
            throw new IOException("Generator registry contains invalid state");
        }
        String uid = text(configMap.path("metadata").path("uid"));
        String version = text(configMap.path("metadata").path("resourceVersion"));
        String highWater = text(configMap.path("data").path("highWaterMark"));
        if (uid == null || version == null || highWater == null || !HIGH_WATER.matcher(highWater).matches()) {
            throw new IOException("Generator registry contains invalid state");
        }
        try {
            return new RegistryState(uid, version, Integer.parseInt(highWater));
        } catch (IllegalArgumentException invalidState) {
            throw new IOException("Generator registry contains invalid state");
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : null;
    }

    private static boolean reserved(byte[] body, RegistryState expected, int next) {
        try {
            RegistryState reserved = parse(body);
            return expected.uid().equals(reserved.uid()) && reserved.highWaterMark() == next
                    && !expected.resourceVersion().equals(reserved.resourceVersion());
        } catch (IOException unknownOutcome) {
            return false;
        }
    }

    /** Tests the UID, the version and the counter atomically with the replacement; JSON escapes each value. */
    private static byte[] patch(RegistryState expected, int next) throws IOException {
        ArrayNode operations = JSON.createArrayNode();
        operations.addObject().put("op", "test").put("path", "/metadata/uid").put("value", expected.uid());
        operations.addObject().put("op", "test").put("path", "/metadata/resourceVersion")
                .put("value", expected.resourceVersion());
        operations.addObject().put("op", "test").put("path", "/data/highWaterMark")
                .put("value", Integer.toString(expected.highWaterMark()));
        operations.addObject().put("op", "replace").put("path", "/data/highWaterMark")
                .put("value", Integer.toString(next));
        return JSON.writeValueAsBytes(operations);
    }

    private static KeyStore trustStore(Path caFile) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream certificates = Files.newInputStream(caFile)) {
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(certificates)) {
                store.setCertificateEntry("ca-" + store.size(), certificate);
            }
        }
        if (store.size() == 0) {
            throw new CertificateException("The CA file contains no certificate");
        }
        return store;
    }

    private record Response(int status, byte[] body) {
    }
}
