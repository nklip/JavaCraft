package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.GatewayApi;
import dev.nklip.javacraft.shardshop.testing.kind.WebpageLayer;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.testcontainers.containers.Container.ExecResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class WebpageStepDefinitions {
    private final GatewayApi api;
    private final Path source = ShardShopFiles.resolve(".");
    private List<String> manifests = List.of();

    public WebpageStepDefinitions(GatewayApi api) {
        this.api = api;
    }

    @Given("a Kubernetes API server with the CRDs of the vendored Envoy Gateway chart")
    public void apiServer() throws IOException, InterruptedException {
        api.start(source);
    }

    @When("the webpages layer is rendered without and with the access values")
    public void render() throws IOException, InterruptedException {
        manifests = List.of(WebpageLayer.render(source, api.directory(), false), WebpageLayer.render(source, api.directory(), true));
    }

    @Then("the API server accepts each rendered resource in a server-side dry run")
    public void accepted() throws IOException, InterruptedException {
        for (String manifest : manifests) {
            ExecResult result = api.dryRun(manifest);
            assertEquals(0, result.getExitCode(), result.getStderr());
            assertTrue(result.getStdout().contains("(server dry run)"), result.getStdout());
        }
        assertTrue(manifests.getLast().contains("kind: SecurityPolicy"), "The second render must contain the role rules");
    }

    @Then("the API server rejects a SecurityPolicy whose role rules have no JWT provider")
    public void rejected() throws IOException, InterruptedException {
        // Proves that the dry run applies the validation rules of the CRDs, thus an accepted policy is valid.
        String policy = manifests.getLast().substring(manifests.getLast().indexOf("kind: SecurityPolicy"));
        String withoutJwt = "apiVersion: gateway.envoyproxy.io/v1alpha1\n"
                + policy.replaceAll("(?ms)^  jwt:\\n.*?(?=^  authorization:)", "");
        assertNotEquals(policy, withoutJwt, "The test must remove the JWT provider");
        ExecResult result = api.dryRun(withoutJwt);
        assertNotEquals(0, result.getExitCode());
        assertTrue(result.getStderr().contains("if authorization.rules.principal.jwt is used, jwt must be defined"), result.getStderr());
    }
}
