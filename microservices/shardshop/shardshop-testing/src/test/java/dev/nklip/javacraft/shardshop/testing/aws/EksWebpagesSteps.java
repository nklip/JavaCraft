package dev.nklip.javacraft.shardshop.testing.aws;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class EksWebpagesSteps {
    @Then("each webpage has a valid certificate, HTTP goes to HTTPS, and a page without a login goes to the Cognito login")
    public void httpsAndLogin() throws IOException, InterruptedException { new EksWebpages().httpsAndLogin(); }

    @Then("no API answers without a login or to a forged token, and a write from another site is rejected")
    public void rejected() throws IOException, InterruptedException { new EksWebpages().rejected(); }

    @Then("each other API and method of the applications gets 404")
    public void onlyIntendedRoutes() throws IOException, InterruptedException { new EksWebpages().onlyIntendedRoutes(); }

    @Then("the load balancer has only private addresses and drops each connection that is not from CloudFront")
    public void onlyCloudFront() throws IOException, InterruptedException { new EksWebpages().onlyCloudFrontReachesTheLoadBalancer(); }

    @Then("the same address answers while the Envoy and product pods are replaced, with one Envoy pod in each zone")
    public void stableAddress() throws IOException, InterruptedException { new EksWebpages().stableAddress(); }
}
