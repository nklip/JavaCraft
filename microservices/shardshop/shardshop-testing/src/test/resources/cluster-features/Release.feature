@kind-release
Feature: Acceptance of the invocation's application release
  The invocation snapshot includes the qualified image digests and desired Helm chart and values.
  These checks observe the current release and preserve its running reader and resources.

  Scenario: The current release has converged to its qualified application and service intent
    Then the current application release runs the invocation's qualified images and desired ready pods
