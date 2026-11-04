Feature: Webpage resources of the AWS deployment
  The webpages layer of the shared chart renders the Gateway API and Envoy Gateway resources of the AWS
  deployment. An unprivileged k3s API server with the CRDs of the vendored Envoy Gateway chart checks the
  schemas and the validation rules of these resources before an AWS run. It runs no controller, thus Envoy
  enforces the routes and the role rules only on EKS (EksWebpages.feature).

  Scenario: The API server accepts the webpage resources of both delivery phases
    Given a Kubernetes API server with the CRDs of the vendored Envoy Gateway chart
    When the webpages layer is rendered without and with the access values
    Then the API server accepts each rendered resource in a server-side dry run
    And the API server rejects a SecurityPolicy whose role rules have no JWT provider
