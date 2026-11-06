Feature: EKS webpages through CloudFront
  These scenarios run only on the AWS deployment of infra/terraform/README.md, after the Terraform webpages
  module. They send the requests from this computer through CloudFront, as a browser does. A request with a
  login needs a Cognito password, thus the operator checks the viewer and operator roles in a browser.

  @eks-webpages
  Scenario: The webpages use HTTPS and a login
    Then each webpage has a valid certificate, HTTP goes to HTTPS, and a page without a login goes to the Cognito login

  @eks-webpages
  Scenario: Requests without a login and cross-site writes cannot read or change a workload
    Then no API answers without a login or to a forged token, and a write from another site is rejected

  @eks-webpages
  Scenario: Only the intended routes and methods are public
    Then each other API and method of the applications gets 404

  @eks-webpages
  Scenario: Only CloudFront reaches the load balancer
    Then the load balancer has only private addresses and drops each connection that is not from CloudFront

  @eks-webpages-replacement
  Scenario: The address stays while the pods are replaced
    Then the same address answers while the Envoy and product pods are replaced, with one Envoy pod in each zone
