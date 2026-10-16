@order-api
Feature: Order validation and atomic acceptance on PostgreSQL primaries
  The packaged order provider uses the runtime role and externally migrated schemas.
  Every response is validated against the order OpenAPI. Acceptance stops at PENDING_STOCK;
  stock reservation, command publication and ledger processing belong to later milestones.

  Background:
    Given order acceptance uses migrated regional primaries and the packaged providers

  Scenario: Buyer and allocation retries survive concurrent requests and a service restart
    Then buyer creation and allocation are durable and regional

  Scenario: Cross-region acceptance saves exact immutable snapshots and only one saga
    Then concurrent order acceptance stores one complete pending order without changing stock
    And pending and terminal retries return the saved status without catalog access

  Scenario: Validation errors obey their precedence and leave allocations reusable
    Then order validation errors leave no orders or sagas
    And catalog unavailability outranks missing products and existing retries bypass it

  Scenario: A failed saga insert rolls back the complete order transaction
    Then a database failure during acceptance leaves only its durable allocation

  Scenario: Concurrent requests wait for a pooled connection instead of returning 503
    Then concurrent acceptances and status reads on healthy primaries never return 503

  Scenario: Creations wait for their advisory locks, and a held lock ends within the phase budget
    Then buyer creation, allocation and order acceptance each wait for their advisory lock
    And an order lock held past the phase budget gives 503 and writes nothing

  Scenario Outline: A locked catalog lookup holds no order resources and the final recheck wins
    Then a concurrent "<change>" wins over a blocked catalog lookup
    Examples:
      | change     |
      | creation   |
      | quarantine |

  Scenario: V2 upgrades legacy ordering data without rewriting its historical snapshots
    Then the ordering migration preserves legacy data and enforces complete new profiles
