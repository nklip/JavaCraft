package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The webpages layer of the shared chart (render=webpages) and the public operations that it must expose: the five
 * pages, the APIs that the pages call, the operations of the workload control contract, and the start, login and
 * logout addresses.
 */
public final class WebpageLayer {
    public static final String HOST = "d111111abcdef8.cloudfront.net";
    public static final String ISSUER = "https://cognito-idp.eu-west-2.amazonaws.com/eu-west-2_Example1";
    public static final String CLIENT = "1example23456789abcdefghij";
    public static final String SECURITY_GROUP = "sg-0123456789abcdef0";
    static final String CONTRACT = "shardshop-core/workload-control/src/main/resources/contracts/openapi.yaml";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A public operation: a method and an exact path, or a method and a path prefix. */
    public record Operation(String method, String path, boolean prefix) { }

    private WebpageLayer() { }

    /** The operations that the routes must expose, and no other operation. */
    public static Set<Operation> expected(Path source) throws IOException {
        Set<Operation> operations = new LinkedHashSet<>(List.of(
                new Operation("GET", "/products", true), new Operation("GET", "/api/v1/products", false),
                new Operation("GET", "/orders", true), new Operation("GET", "/api/v1/orders", false),
                // The script and the stylesheet of the three workload pages.
                new Operation("GET", "/workload-control", true),
                new Operation("GET", "/", false), new Operation("GET", "/oauth2/callback", false), new Operation("GET", "/logout", false)));
        Map<String, Object> contract = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(source.resolve(CONTRACT)));
        for (Object server : (List<?>) contract.get("servers")) {
            String root = String.valueOf(((Map<?, ?>) server).get("url"));
            String workload = root.substring(root.lastIndexOf('/') + 1);
            operations.add(new Operation("GET", "/workload-" + workload, true));
            for (Map.Entry<?, ?> path : ((Map<?, ?>) contract.get("paths")).entrySet()) {
                // The contract: "Start one finite seed run (interactive seeder only)"; the producer and the reader return 404.
                if (path.getKey().equals("/control/runs") && !workload.equals("product-seeder")) continue;
                for (Object method : ((Map<?, ?>) path.getValue()).keySet()) {
                    // A path item can also have a summary or parameters.
                    if (!Set.of("get", "put", "post", "delete", "patch").contains(String.valueOf(method))) continue;
                    operations.add(new Operation(String.valueOf(method).toUpperCase(Locale.ROOT), root + path.getKey(), false));
                }
            }
        }
        return operations;
    }

    /** The paths of the five pages. */
    public static List<String> pages(Set<Operation> operations) {
        return operations.stream().filter(Operation::prefix).map(Operation::path).filter(path -> !path.equals("/workload-control"))
                .map(path -> path + "/").toList();
    }

    /** Renders the layer with the EKS values. Without the access values, it renders the state before the Terraform webpages module. */
    public static String render(Path source, Path work, boolean access) throws IOException, InterruptedException {
        Map<String, Object> webpages = new LinkedHashMap<>(Map.of("securityGroup", SECURITY_GROUP));
        if (access) webpages.put("access", Map.of("hostname", HOST, "issuer", ISSUER, "clientId", CLIENT));
        Path values = Files.createTempFile(work, "webpages", ".json");
        Files.writeString(values, JSON.writeValueAsString(Map.of("webpages", webpages)));
        Path chart = source.resolve("infra/helm/shardshop");
        List<String> command = new ArrayList<>(List.of("helm", "template", "shardshop-webpages", chart.toString(), "--namespace", "shardshop",
                "--values", source.resolve("infra/shards.yaml").toString(), "--values", chart.resolve("eks-values.yaml").toString(),
                "--values", values.toString(), "--set-string", "delivery=argocd", "--set-string", "render=webpages"));
        Path output = Files.createTempFile(work, "webpages", ".yaml");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("helm template exceeded its deadline");
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException("helm template failed: " + text);
            return text;
        } finally {
            process.destroyForcibly();
        }
    }
}
