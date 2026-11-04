package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.kind.WebpageLayer.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The webpages layer of the AWS deployment: only the intended routes and methods, one login and role policy for all of
 * them, and Envoy pods in each zone behind an internal NLB. Envoy enforces these rules only on EKS.
 */
class WebpagesChartTest {
    private static final Path SOURCE = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
    @TempDir Path temporary;

    @Test
    void noRouteExistsBeforeTheLoginConfiguration() throws Exception {
        List<JsonNode> resources = render(false);
        assertEquals(Set.of("GatewayClass", "EnvoyProxy", "Gateway"), resources.stream().map(resource -> resource.path("kind").asText())
                        .collect(java.util.stream.Collectors.toSet()),
                "Before Terraform creates CloudFront and Cognito, a route would be open without a login");
    }

    @Test
    void theRoutesExposeOnlyTheIntendedMethodsAndPaths() throws Exception {
        List<JsonNode> routes = ofKind(render(true), "HTTPRoute");
        List<Operation> exposed = new ArrayList<>();
        Map<String, String> services = Map.of("/products", "shardshop-product", "/orders", "shardshop-order",
                "/workload-order-producer", "shardshop-order-producer", "/workload-product-reader", "shardshop-product-reader",
                "/workload-product-seeder", "shardshop-product-seeder-control");
        for (JsonNode route : routes) {
            JsonNode spec = route.path("spec");
            assertEquals("[\"" + WebpageLayer.HOST + "\"]", spec.path("hostnames").toString(), "Only the CloudFront name");
            assertEquals("shardshop-webpages", spec.path("parentRefs").path(0).path("name").asText());
            assertEquals("1", route.path("metadata").path("annotations").path("argocd.argoproj.io/sync-wave").asText(),
                    "The SecurityPolicy must exist before a route");
            for (JsonNode rule : spec.path("rules")) {
                for (JsonNode match : rule.path("matches")) {
                    assertFalse(match.path("method").asText().isEmpty(), "A match without a method accepts each method");
                    Operation operation = new Operation(match.path("method").asText(), match.path("path").path("value").asText(),
                            match.path("path").path("type").asText().equals("PathPrefix"));
                    exposed.add(operation);
                    String backend = rule.path("backendRefs").path(0).path("name").asText();
                    services.forEach((page, service) -> {
                        if (operation.prefix() && operation.path().equals(page)) assertEquals(service, backend, page);
                    });
                }
            }
        }
        assertEquals(exposed.size(), new HashSet<>(exposed).size(), "Each operation has one route");
        assertEquals(WebpageLayer.expected(SOURCE), Set.copyOf(exposed));
        assertEquals(5, WebpageLayer.pages(WebpageLayer.expected(SOURCE)).size());
    }

    @Test
    void onePolicyOnTheGatewayGivesTheLoginTheRoleRulesAndTheCsrfCheckToEachRoute() throws Exception {
        List<JsonNode> policies = ofKind(render(true), "SecurityPolicy");
        assertEquals(1, policies.size(), "Two policies with a login on one host break the login (envoyproxy/gateway#8649)");
        JsonNode spec = policies.getFirst().path("spec");
        assertEquals("Gateway", spec.path("targetRefs").path(0).path("kind").asText(), "The policy must cover each route");
        assertEquals("shardshop-webpages", spec.path("targetRefs").path(0).path("name").asText());
        assertTrue(spec.has("csrf"), "A cross-site PUT or POST must get 403");
        assertFalse(spec.path("csrf").has("additionalOrigins"), "Only the own origin can change a workload");

        JsonNode oidc = spec.path("oidc");
        assertEquals(WebpageLayer.ISSUER, oidc.path("provider").path("issuer").asText());
        assertEquals(WebpageLayer.CLIENT, oidc.path("clientID").asText());
        assertEquals("shardshop-webpages-oidc", oidc.path("clientSecret").path("name").asText());
        assertEquals("https://" + WebpageLayer.HOST + "/oauth2/callback", oidc.path("redirectURL").asText());
        assertEquals("Lax", oidc.path("cookieConfig").path("sameSite").asText(),
                "Strict would drop the login state on the redirect from Cognito; None would send the tokens cross-site");
        assertEquals("Authorization", oidc.path("forwardIDToken").path("header").asText(), "The JWT filter reads the validated ID token");
        assertFalse(oidc.path("passThroughAuthHeader").asBoolean(false), "A client token must not skip the login");
        assertFalse(oidc.path("disableTokenEncryption").asBoolean(false));

        JsonNode provider = spec.path("jwt").path("providers").path(0);
        assertEquals(WebpageLayer.ISSUER, provider.path("issuer").asText());
        assertEquals("[\"" + WebpageLayer.CLIENT + "\"]", provider.path("audiences").toString(), "Only an ID token of this client");
        assertEquals(WebpageLayer.ISSUER + "/.well-known/jwks.json", provider.path("remoteJWKS").path("uri").asText());

        JsonNode authorization = spec.path("authorization");
        assertEquals("Deny", authorization.path("defaultAction").asText());
        Map<String, Object> webpages = eksWebpages();
        Set<String> exposedMethods = new HashSet<>();
        WebpageLayer.expected(SOURCE).forEach(operation -> exposedMethods.add(operation.method()));
        Set<String> allowedMethods = new HashSet<>();
        for (JsonNode rule : authorization.path("rules")) {
            assertEquals("Allow", rule.path("action").asText());
            JsonNode claim = rule.path("principal").path("jwt").path("claims").path(0);
            assertEquals("cognito", rule.path("principal").path("jwt").path("provider").asText());
            assertEquals("cognito:groups", claim.path("name").asText());
            assertEquals("StringArray", claim.path("valueType").asText(), "Cognito gives the groups as a list");
            List<String> methods = new ArrayList<>();
            rule.path("operation").path("methods").forEach(method -> methods.add(method.asText()));
            allowedMethods.addAll(methods);
            List<String> groups = new ArrayList<>();
            claim.path("values").forEach(group -> groups.add(group.asText()));
            if (methods.equals(List.of("GET"))) {
                assertEquals(webpages.get("readGroups"), groups);
            } else {
                assertEquals(List.of("PUT", "POST"), methods);
                assertEquals(webpages.get("writeGroups"), groups);
                assertFalse(groups.contains("viewer"), "A viewer must not change a workload");
            }
        }
        assertEquals(exposedMethods, allowedMethods, "Each exposed method has a role rule");
        assertTrue(((List<?>) webpages.get("readGroups")).containsAll((List<?>) webpages.get("writeGroups")),
                "An operator must also open the pages");
    }

    @Test
    void envoyRunsInEachZoneBehindAnInternalLoadBalancerOfCloudFront() throws Exception {
        List<JsonNode> resources = render(true);
        JsonNode kubernetes = ofKind(resources, "EnvoyProxy").getFirst().path("spec").path("provider").path("kubernetes");
        JsonNode deployment = kubernetes.path("envoyDeployment");
        assertEquals(3, deployment.path("replicas").asInt());
        assertEquals(0, deployment.path("strategy").path("rollingUpdate").path("maxUnavailable").asInt(-1),
                "A rollout keeps three serving pods");
        JsonNode spread = deployment.path("pod").path("topologySpreadConstraints").path(0);
        assertEquals("topology.kubernetes.io/zone", spread.path("topologyKey").asText());
        assertEquals("DoNotSchedule", spread.path("whenUnsatisfiable").asText());
        assertEquals(1, spread.path("maxSkew").asInt());
        assertEquals(1, kubernetes.path("envoyPDB").path("maxUnavailable").asInt(), "A node drain stops one Envoy pod at a time");

        JsonNode service = kubernetes.path("envoyService");
        JsonNode annotations = service.path("annotations");
        assertEquals("LoadBalancer", service.path("type").asText());
        assertEquals("service.k8s.aws/nlb", service.path("loadBalancerClass").asText());
        assertFalse(service.path("allocateLoadBalancerNodePorts").asBoolean(true), "A node port would bypass the NLB security group");
        assertEquals("internal", annotations.path("service.beta.kubernetes.io/aws-load-balancer-scheme").asText());
        assertEquals("ip", annotations.path("service.beta.kubernetes.io/aws-load-balancer-nlb-target-type").asText());
        assertEquals(WebpageLayer.SECURITY_GROUP, annotations.path("service.beta.kubernetes.io/aws-load-balancer-security-groups").asText());
        String name = annotations.path("service.beta.kubernetes.io/aws-load-balancer-name").asText();
        Matcher lookup = Pattern.compile("data \"aws_lb\" \"webpages\" \\{\\s+name = \"\\$\\{local.name}-([a-z-]+)\"")
                .matcher(Files.readString(SOURCE.resolve("infra/terraform/webpages/main.tf")));
        assertTrue(lookup.find(), "The Terraform webpages module finds the NLB by its name");
        assertEquals("shardshop-" + lookup.group(1), name);

        Matcher delay = Pattern.compile("deregistration_delay.timeout_seconds=([0-9]+)")
                .matcher(annotations.path("service.beta.kubernetes.io/aws-load-balancer-target-group-attributes").asText());
        assertTrue(delay.find());
        JsonNode shutdown = ofKind(resources, "EnvoyProxy").getFirst().path("spec").path("shutdown");
        assertTrue(seconds(shutdown.path("minDrainDuration").asText()) > Integer.parseInt(delay.group(1)),
                "Envoy must accept connections until the NLB stops sending new ones");
        assertTrue(seconds(shutdown.path("drainTimeout").asText()) >= seconds(shutdown.path("minDrainDuration").asText()));

        JsonNode gateway = ofKind(resources, "Gateway").getFirst().path("spec");
        assertEquals("EnvoyProxy", gateway.path("infrastructure").path("parametersRef").path("kind").asText());
        assertEquals("[{\"name\":\"http\",\"protocol\":\"HTTP\",\"port\":80,\"allowedRoutes\":{\"namespaces\":{\"from\":\"Same\"}}}]",
                gateway.path("listeners").toString(), "CloudFront ends TLS; only the routes of this namespace");
    }

    private List<JsonNode> render(boolean access) throws Exception {
        return KindSafety.infrastructureManifest(WebpageLayer.render(SOURCE, temporary, access));
    }

    private static Map<String, Object> eksWebpages() throws Exception {
        Map<String, Object> values = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(SOURCE.resolve("infra/helm/shardshop/eks-values.yaml")));
        @SuppressWarnings("unchecked") Map<String, Object> webpages = (Map<String, Object>) values.get("webpages");
        return webpages;
    }

    private static int seconds(String duration) {
        assertTrue(duration.matches("[0-9]+s"), duration);
        return Integer.parseInt(duration.substring(0, duration.length() - 1));
    }

    private static List<JsonNode> ofKind(List<JsonNode> resources, String kind) {
        return resources.stream().filter(resource -> kind.equals(resource.path("kind").asText())).toList();
    }
}
