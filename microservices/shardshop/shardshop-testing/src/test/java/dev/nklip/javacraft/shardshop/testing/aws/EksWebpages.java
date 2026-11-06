package dev.nklip.javacraft.shardshop.testing.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.testing.support.WebpageLayer.Operation;
import dev.nklip.javacraft.shardshop.testing.cluster.ClusterLab;
import dev.nklip.javacraft.shardshop.testing.support.WebpageLayer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.nklip.javacraft.shardshop.testing.cluster.Topology.require;

/**
 * EKS-only checks of the public webpages, from this computer through CloudFront as a browser sends them: HTTPS, the
 * redirects to HTTPS and to the login, the rejection of requests without a login and of cross-site writes, only the
 * intended routes, no path to the load balancer except CloudFront, and the same address after pod replacements. A
 * request with a login needs a Cognito password, thus the operator checks the roles in a browser (README).
 */
public final class EksWebpages {
    static final String LAYER = "shardshop-webpages";
    private static final Path SOURCE = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
    private static final String GATEWAY = "shardshop-webpages";
    private static final String ENVOY = "envoy-gateway-system";
    private static final String ENVOY_PODS = "gateway.envoyproxy.io/owning-gateway-name=" + GATEWAY;
    private static final String COPIED_URL = "/products/?region=EU&page=3";
    private static final Duration REPLACEMENT = Duration.ofMinutes(10);
    private final ClusterLab lab;
    // HTTP/1.1 also on port 80: the JDK client would otherwise send an h2c upgrade.
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

    public EksWebpages() { this(new ClusterLab()); }
    EksWebpages(ClusterLab lab) { this.lab = lab; }

    /** The default certificate of CloudFront is valid for the host, HTTP goes to HTTPS, and a page without a login goes to Cognito. */
    public void httpsAndLogin() throws IOException, InterruptedException {
        Access access = access();
        List<String> pages = new ArrayList<>(WebpageLayer.pages(WebpageLayer.expected(SOURCE)));
        pages.add(COPIED_URL);
        for (String page : pages) {
            HttpResponse<String> plain = send("GET", "http://" + access.host() + page, Map.of(), null);
            same(plain.statusCode(), 301, "HTTP status of " + page);
            same(plain.headers().firstValue("location").orElse(""), "https://" + access.host() + page, "HTTPS redirect of " + page);

            // The JDK client checks the certificate chain and the host name; a failed check throws an exception.
            HttpResponse<String> secure = send("GET", "https://" + access.host() + page, Map.of("Sec-Fetch-Mode", "navigate"), null);
            X509Certificate certificate = (X509Certificate) secure.sslSession().orElseThrow().getPeerCertificates()[0];
            require(certificate.getSubjectX500Principal().getName().contains("CN=*.cloudfront.net"), "Certificate of " + page);
            same(secure.statusCode(), 302, "Status of " + page + " without a login");
            URI login = URI.create(secure.headers().firstValue("location").orElse(""));
            Map<String, String> query = query(login.getRawQuery());
            require(login.getScheme().equals("https") && login.getHost().endsWith(".amazoncognito.com")
                    && login.getPath().equals("/oauth2/authorize"), page + " must go to the Cognito login: " + login);
            same(query.get("client_id"), access.clientId(), "Client of the login");
            same(query.get("redirect_uri"), "https://" + access.host() + "/oauth2/callback", "Return address of the login");
            require(query.getOrDefault("scope", "").contains("openid"), "The login must ask for the ID token");
            require(originalUrl(query.get("state")).endsWith(access.host() + page),
                    "After the login, the browser must come back to " + page + ", not to " + originalUrl(query.get("state")));
        }
    }

    /** No API answers without a login, a forged token is not accepted, and a cross-site write is rejected before the login. */
    public void rejected() throws IOException, InterruptedException {
        Access access = access();
        String origin = "https://" + access.host();
        String forged = "Bearer " + unsigned("{\"cognito:groups\":[\"operator\"],\"aud\":\"" + access.clientId() + "\"}");
        for (Operation operation : WebpageLayer.expected(SOURCE)) {
            if (operation.prefix() || !operation.path().startsWith("/api/")) continue;
            String url = origin + operation.path();
            String body = operation.method().equals("PUT") ? "{\"targetRate\":1}" : null;
            Map<String, String> script = Map.of("Origin", origin, "Sec-Fetch-Mode", "cors", "Content-Type", "application/json");
            same(send(operation.method(), url, script, body).statusCode(), 401, operation + " without a login");
            Map<String, String> token = new HashMap<>(script);
            token.put("Authorization", forged);
            same(send(operation.method(), url, token, body).statusCode(), 401, operation + " with a forged token");
            if (!operation.method().equals("GET")) {
                Map<String, String> crossSite = new HashMap<>(script);
                crossSite.put("Origin", "https://attacker.example");
                same(send(operation.method(), url, crossSite, body).statusCode(), 403, operation + " from another site");
            }
        }
    }

    /** Other APIs of the applications and other methods are not public: Envoy has no route for them. */
    public void onlyIntendedRoutes() throws IOException, InterruptedException {
        Access access = access();
        String origin = "https://" + access.host();
        List<Operation> hidden = List.of(new Operation("GET", "/api/v1/products/1", false), new Operation("POST", "/api/v1/products", false),
                new Operation("PUT", "/api/v1/orders/1", false), new Operation("GET", "/api/v1/orders/1", false),
                new Operation("GET", "/q/health", false), new Operation("GET", "/q/metrics", false),
                new Operation("GET", "/api/v1/order-producer/control/rate", false),
                new Operation("DELETE", "/api/v1/order-producer/control/rate", false),
                new Operation("PATCH", "/api/v1/product-seeder/control/rate", false),
                new Operation("POST", "/api/v1/order-producer/control/runs", false),
                new Operation("POST", "/api/v1/product-reader/control/runs", false),
                new Operation("POST", "/products/", false), new Operation("GET", "/admin", false));
        Set<Operation> exposed = WebpageLayer.expected(SOURCE);
        for (Operation operation : hidden) {
            require(!exposed.contains(operation), operation + " is an intended operation");
            HttpResponse<String> response = send(operation.method(), origin + operation.path(),
                    Map.of("Origin", origin, "Sec-Fetch-Mode", "cors"), operation.method().equals("GET") ? null : "{}");
            same(response.statusCode(), 404, operation.toString());
        }
    }

    /** The NLB has only private addresses, and inside the VPC its security group drops each connection that is not from CloudFront. */
    public void onlyCloudFrontReachesTheLoadBalancer() throws IOException, InterruptedException {
        String balancer = lab.kubectl("get", "gateway", GATEWAY, "-o", "jsonpath={.status.addresses[0].value}").strip();
        // The name of an internal NLB has no "internal-" prefix (an ALB name has it); its addresses show the scheme.
        require(balancer.startsWith("shardshop-webpages-") && balancer.endsWith(".elb.eu-west-2.amazonaws.com"), "NLB name: " + balancer);
        InetAddress[] addresses = InetAddress.getAllByName(balancer);
        String service = lab.kubectl("-n", ENVOY, "get", "service", "-l", ENVOY_PODS, "-o", "jsonpath={.items[0].spec.clusterIP}").strip();
        // A positive control: the same probe from the same pod reaches the Envoy Service inside the cluster.
        require(tcp(service), "The probe must reach the Envoy Service " + service + " from the broker pod");
        for (InetAddress address : addresses) {
            require(address.isSiteLocalAddress(), balancer + " has a public address " + address.getHostAddress());
            require(!tcp(address.getHostAddress()), "A pod in the VPC reached the NLB at " + address.getHostAddress());
        }
    }

    /** One Envoy pod in each zone, and the same address answers while Envoy and the product pods are replaced. */
    public void stableAddress() throws IOException, InterruptedException {
        Access access = access();
        String balancer = lab.kubectl("get", "gateway", GATEWAY, "-o", "jsonpath={.status.addresses[0].value}").strip();
        envoyInEachZone();
        List<String> failures = new CopyOnWriteArrayList<>();
        AtomicInteger answers = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread poller = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    HttpResponse<String> response = send("GET", "https://" + access.host() + "/products/", Map.of("Sec-Fetch-Mode", "navigate"), null);
                    if (response.statusCode() == 302) answers.incrementAndGet();
                    else failures.add(Instant.now() + " HTTP " + response.statusCode());
                } catch (IOException failure) {
                    failures.add(Instant.now() + " " + failure);
                } catch (InterruptedException interrupted) {
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
        try {
            for (String pod : names(lab.kubectl("-n", ENVOY, "get", "pods", "-l", ENVOY_PODS, "-o", "json"))) {
                lab.kubectl("-n", ENVOY, "delete", "pod", pod, "--wait=false");
                lab.await("a ready replacement of Envoy pod " + pod, REPLACEMENT, () -> readyPods(ENVOY, ENVOY_PODS).size() == 3
                        && !readyPods(ENVOY, ENVOY_PODS).contains(pod));
            }
            String product = "app.kubernetes.io/name=shardshop-product";
            List<String> products = readyPods("shardshop", product);
            for (String pod : products) {
                lab.kubectl("delete", "pod", pod, "--wait=false");
                lab.await("a ready replacement of product pod " + pod, REPLACEMENT, () -> readyPods("shardshop", product).size() == products.size()
                        && !readyPods("shardshop", product).contains(pod));
            }
        } finally {
            running.set(false);
            poller.join(Duration.ofSeconds(30));
        }
        require(failures.isEmpty(), "Requests failed during the pod replacements: " + failures);
        require(answers.get() >= 20, "Too few requests during the pod replacements: " + answers.get());
        envoyInEachZone();
        same(lab.kubectl("get", "gateway", GATEWAY, "-o", "jsonpath={.status.addresses[0].value}").strip(), balancer, "Load balancer name");
        same(access().host(), access.host(), "Webpage host name");
        for (JsonNode route : ClusterLab.items(ClusterLab.parse(lab.kubectl("get", "httproutes", "-o", "json")))) {
            for (JsonNode condition : route.path("status").path("parents").path(0).path("conditions")) {
                require(condition.path("status").asText().equals("True"), route.path("metadata").path("name").asText() + " " + condition);
            }
        }
    }

    private void envoyInEachZone() throws IOException, InterruptedException {
        Set<String> zones = new HashSet<>();
        for (JsonNode pod : ClusterLab.items(ClusterLab.parse(lab.kubectl("-n", ENVOY, "get", "pods", "-l", ENVOY_PODS, "-o", "json")))) {
            if (pod.path("metadata").has("deletionTimestamp")) continue;
            String node = pod.path("spec").path("nodeName").asText();
            zones.add(lab.kubectl("get", "node", node, "-o", "jsonpath={.metadata.labels.topology\\.kubernetes\\.io/zone}").strip());
        }
        same(zones.size(), 3, "Zones of the Envoy pods " + zones);
    }

    private List<String> readyPods(String namespace, String selector) throws IOException, InterruptedException {
        List<String> ready = new ArrayList<>();
        for (JsonNode pod : ClusterLab.items(ClusterLab.parse(lab.kubectl("-n", namespace, "get", "pods", "-l", selector, "-o", "json")))) {
            if (pod.path("metadata").has("deletionTimestamp")) continue;
            for (JsonNode condition : pod.path("status").path("conditions")) {
                if (condition.path("type").asText().equals("Ready") && condition.path("status").asText().equals("True")) {
                    ready.add(pod.path("metadata").path("name").asText());
                }
            }
        }
        return ready;
    }

    private static List<String> names(String json) {
        return ClusterLab.items(ClusterLab.parse(json)).stream().map(item -> item.path("metadata").path("name").asText()).toList();
    }

    /** A TCP connection from the first broker pod, which has bash. The pod address is in the VPC. */
    private boolean tcp(String address) throws IOException, InterruptedException {
        return lab.kubectlAttempt(Duration.ofSeconds(20), "exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--", "timeout", "5", "bash", "-c",
                "exec 3<>/dev/tcp/" + address + "/80").exitCode() == 0;
    }

    private HttpResponse<String> send(String method, String url, Map<String, String> headers, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The host, the client and the issuer that the SecurityPolicy in the cluster uses. */
    private Access access() throws IOException, InterruptedException {
        JsonNode oidc = ClusterLab.parse(lab.kubectl("get", "securitypolicy", GATEWAY, "-o", "json")).path("spec").path("oidc");
        URI redirect = URI.create(oidc.path("redirectURL").asText());
        require(redirect.getHost() != null && redirect.getHost().endsWith(".cloudfront.net"), "SecurityPolicy redirect URL: " + redirect);
        return new Access(redirect.getHost(), oidc.path("clientID").asText());
    }

    private record Access(String host, String clientId) { }

    static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    /** The original address in the OAuth2 state of Envoy: base64url JSON with "url", or a form-encoded "url". */
    static String originalUrl(String state) {
        if (state == null) return "";
        try {
            JsonNode json = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(state));
            if (json != null && json.hasNonNull("url")) return json.path("url").asText();
        } catch (IllegalArgumentException | IOException ignored) {
            // Not base64url JSON; the older form is form-encoded.
        }
        return query(state).getOrDefault("url", "");
    }

    private static void same(Object actual, Object expected, String description) throws IOException {
        require(expected.equals(actual), description + ": expected " + expected + ", got " + actual);
    }

    private static String unsigned(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".";
    }
}
