@order-api @order-browser
Feature: Regional order page packaged with the order service
  The read-only page, stylesheet and script ship together with the provider.
  Deployments must never mix cached assets with new markup.

  Scenario: The packaged order serves the page and both versioned assets without caching
    Given order acceptance uses migrated regional primaries and the packaged providers
    Then the packaged order serves its regional browser and uncached local assets
