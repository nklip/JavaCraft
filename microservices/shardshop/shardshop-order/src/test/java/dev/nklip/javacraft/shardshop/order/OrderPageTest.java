package dev.nklip.javacraft.shardshop.order;

import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OrderPageTest {

    @TestHTTPResource("/")
    URI root;

    @ParameterizedTest
    @ValueSource(strings = {"orders/", "orders/index.html"})
    void servesThePageAndItsAssetsWithoutStoringStaleVersions(String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> page = get(client, root.resolve(path), "text/html");
            URI stylesheet = linkedAsset(page.body(), "href", "orders.css");
            URI script = linkedAsset(page.body(), "src", "orders.js");
            HttpResponse<String> css = get(client, stylesheet, "text/css");
            HttpResponse<String> js = get(client, script, "javascript");

            assertAll(
                    () -> assertNotStored(page),
                    () -> assertNotStored(css),
                    () -> assertNotStored(js),
                    () -> assertVersioned(stylesheet),
                    () -> assertVersioned(script));
        }
    }

    /** The page repeats the zero-minor-unit currencies of the shared policy; a policy change must change the page. */
    @Test
    void packagedScriptOmitsDecimalsForTheZeroMinorUnitCurrenciesOfThePolicy() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> page = get(client, root.resolve("orders/"), "text/html");
            String script = get(client, linkedAsset(page.body(), "src", "orders.js"), "javascript").body();
            Matcher list = Pattern.compile("\\[(\"[A-Z]{3}\"(?:, \"[A-Z]{3}\")*)]\\.includes\\(currency\\)").matcher(script);
            assertTrue(list.find(), "The script must name its zero-minor-unit currencies");
            Set<String> scripted = Arrays.stream(list.group(1).split(", ")).map(code -> code.replace("\"", ""))
                    .collect(Collectors.toSet());
            assertFalse(list.find(), "The script must name its zero-minor-unit currencies once");
            assertEquals(CurrencyPolicy.SUPPORTED_CURRENCIES.stream().filter(code -> CurrencyPolicy.fractionDigits(code) == 0)
                    .collect(Collectors.toSet()), scripted);
        }
    }

    private URI linkedAsset(String html, String attribute, String filename) {
        Matcher reference = Pattern.compile(attribute + "=\"(/orders/" + Pattern.quote(filename)
                + "(?:\\?[^\"]*)?)\"").matcher(html);
        assertTrue(reference.find(), "The page must reference its packaged " + filename);
        return root.resolve(reference.group(1));
    }

    private HttpResponse<String> get(HttpClient client, URI uri, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), uri.toString());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains(contentType),
                () -> "Unexpected content type for " + uri + ": " + response.headers());
        assertFalse(response.body().isBlank(), "Packaged resource must not be empty: " + uri);
        return response;
    }

    private static void assertNotStored(HttpResponse<String> response) {
        List<String> directives = response.headers().allValues("Cache-Control").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
        assertAll(response.uri().toString(),
                () -> assertTrue(directives.contains("no-store"), "Expected no-store: " + directives),
                () -> assertFalse(directives.contains("immutable"), "Must not be immutable: " + directives),
                () -> assertFalse(directives.stream().anyMatch(value -> value.startsWith("max-age=")),
                        "Must not retain a freshness lifetime: " + directives));
    }

    private static void assertVersioned(URI asset) {
        String query = asset.getRawQuery();
        assertTrue(query != null && Pattern.compile("(?:^|&)v=[^&]+").matcher(query).find(),
                () -> "A nonempty asset version must bypass previously cached URLs: " + asset);
    }
}
