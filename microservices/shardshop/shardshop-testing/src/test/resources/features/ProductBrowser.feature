@product-browser
Feature: Regional product browser over the packaged provider
  Arbitrary service-created products are listed across every shard in the seller's
  region. Prices and applied per-product profit remain exact decimal strings.

  Background:
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries

  Scenario: All regional shards participate in stable keyset pages with exact product earnings
    Given arbitrary browser products occupy both US shards and the other regions
    Then the regional browser paginates every stored product once with exact product earnings
    And the regional browser rejects malformed filters and canonical ID violations

  Scenario: An empty region has no next cursor
    Then an empty region returns an empty terminal browser page

  Scenario: A missing regional shard never gives a successful partial page
    Given arbitrary browser products occupy both US shards and the other regions
    Then one unavailable US shard fails the complete regional page while EU remains available

  Scenario: Strict replica reads never fall back to healthy primaries
    Given arbitrary browser products occupy both US shards and the other regions
    Then unavailable replicas fail browser reads without falling back to healthy primaries

  Scenario: The browser and its assets are included in the packaged provider
    Then the packaged product serves the browser page and its local script and stylesheet
