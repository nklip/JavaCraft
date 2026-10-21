@order-listing
Feature: Regional order pages over migrated PostgreSQL shards
  Orders belong to the buyer's home region even when their products come from
  sellers elsewhere. Each page is globally sorted before its cursor is selected.

  Background:
    Given the packaged order browser uses four migrated regional shards

  Scenario: Product filters and both date directions traverse every regional order exactly once
    Given arbitrary orders with tied creation times occupy every regional shard
    Then order pages match the regional orders in both date directions with and without a product filter
    And product names filter literal snapshot substrings across globally sorted pages
    And order listing rejects invalid and ambiguous query parameters without changing orders

  Scenario: Empty regions and unmatched products have no continuation
    Then an empty order region returns a terminal page

  Scenario: A failed shard cannot produce a successful partial order page
    Given arbitrary orders with tied creation times occupy every regional shard
    Then a missing US order shard fails the whole listing while other regions remain readable
