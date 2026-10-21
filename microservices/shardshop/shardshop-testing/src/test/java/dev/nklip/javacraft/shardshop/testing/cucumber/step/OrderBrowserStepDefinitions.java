package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import io.cucumber.java.en.Then;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the deployed page resources through the packaged order HTTP server. */
public final class OrderBrowserStepDefinitions {
    private final OrderRuntime order;

    public OrderBrowserStepDefinitions(OrderRuntime order) {
        this.order = order;
    }

    @Then("the packaged order serves its regional browser and uncached local assets")
    public void packagedResources() throws Exception {
        for (String path : List.of("/orders/", "/orders/index.html")) {
            var response = get(path);
            assertEquals(200, response.status(), response.text());
            assertTrue(response.header("content-type").orElse("").contains("text/html"));
            assertEquals("no-store", response.header("cache-control").orElse(""));
            assertTrue(response.text().contains("Buyer home region"));
            assertTrue(response.text().contains("Product ID or name"));
            assertTrue(response.text().contains("Exact product ID or part of a product name"));
            assertTrue(response.text().contains("id=\"created-heading\" scope=\"col\" aria-sort=\"descending\""));
            assertTrue(response.text().contains("id=\"sort\" class=\"sort-button\" type=\"button\""));
            assertTrue(response.text().contains("Created · UTC"));
            assertFalse(Pattern.compile("<select[^>]*id=[\"']sort[\"']").matcher(response.text()).find());
            var references = Pattern.compile("(?:src|href)=[\"']([^\"']+\\.(?:js|css)(?:\\?[^\"']*)?)[\"']")
                    .matcher(response.text());
            int scripts = 0;
            int styles = 0;
            while (references.find()) {
                String target = references.group(1);
                assertTrue(target.startsWith("/orders/"), "Assets must be packaged under /orders/");
                assertFalse(target.contains(":"), "Browser assets must be local");
                URI assetUri = URI.create(target);
                assertNotNull(assetUri.getQuery(), "Asset versions bypass old cached deployments");
                var asset = get(target);
                assertEquals(200, asset.status(), target);
                assertFalse(asset.text().isBlank());
                assertEquals("no-store", asset.header("cache-control").orElse(""), target);
                String type = asset.header("content-type").orElse("");
                if (assetUri.getPath().endsWith(".js")) {
                    assertTrue(type.contains("javascript"), type);
                    scripts++;
                } else {
                    assertTrue(type.contains("text/css"), type);
                    styles++;
                }
            }
            assertEquals(1, scripts, "The page must load its packaged script");
            assertEquals(1, styles, "The page must load its packaged stylesheet");
        }
    }

    private RawHttp.Response get(String path) throws Exception {
        return RawHttp.exchange(order.port(), "GET", path, List.of(), new byte[0], RawHttp.Framing.NONE);
    }
}
