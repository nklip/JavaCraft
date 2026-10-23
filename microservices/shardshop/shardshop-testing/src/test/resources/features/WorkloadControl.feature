@workload-control
Feature: Live traffic control of the packaged workloads
  Each workload serves its own control API and page. A rate change, Pause and Resume take effect without a restart,
  change only the workload that receives them, and keep the bounded concurrency, deadlines and retry budgets. The
  scenarios measure the backend traffic through the control status of the workload. Every control response must match
  the workload control contract. After each scenario, hooks stop the workloads and the providers and check that none
  of their JVMs logged a warning or an error.

  Scenario: The reader follows live rate changes, Pause and Resume, rejects invalid updates and restarts at its default
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries
    When the seeder runs
    Then the seeder exits with code 0 and logs the summary "US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, KRW=4, JPY=14, VND=1}"
    When the reader starts with an initial rate of 50
    Then the product-reader control shows RUNNING at a target of 50 "product read starts/s" within 90 seconds
    And the product-reader sends 40 to 55 "reads" requests per second
    When the product-reader target rate becomes 200
    Then the product-reader sends 175 to 205 "reads" requests per second
    When the product-reader target rate becomes 10
    Then the product-reader sends 7 to 12 "reads" requests per second
    When the product-reader is paused
    Then the product-reader shows PAUSED within 10 seconds and sends no request for 2 seconds
    When the product-reader resumes
    Then the product-reader sends at most 2 "reads" requests in its first 100 milliseconds
    And the product-reader sends 7 to 12 "reads" requests per second
    When the product-reader target rate becomes 0
    Then the product-reader shows PAUSED within 10 seconds and sends no request for 2 seconds
    And invalid product-reader updates are rejected with 400 without changing its target 0 or its revision
    When 20 concurrent product-reader updates set the target rates 1 to 20
    Then the product-reader revision grew by exactly 20 and its target is one of the rates 1 to 20
    When the reader restarts with an initial rate of 50
    Then the product-reader control shows RUNNING at a target of 50 "product read starts/s" within 90 seconds
    And the product-reader shows revision 0, unpaused

  @order-producer
  Scenario: The producer paces concurrent writes, observes sagas while paused and changes only its own traffic
    Given the order producer has migrated regional primaries and packaged providers
    When the seeder runs
    Then the seeder exits with code 0 and logs the summary "US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, KRW=4, JPY=14, VND=1}"
    When an SQL confirmer confirms each pending order of the run "control-acceptance"
    And the normal order producer "control-acceptance" starts with an initial rate of 2
    And the reader starts with an initial rate of 20
    Then the order-producer control shows RUNNING at a target of 2 "new order starts/s" within 360 seconds
    And the order-producer starts 1.5 to 2.2 new orders per second
    When the order-producer target rate becomes 10
    Then the order-producer starts 7 to 11 new orders per second
    When the order-producer target rate becomes 1
    Then the order-producer starts 0.7 to 1.1 new orders per second
    When the SQL confirmer pauses and the order-producer is paused with a pending saga
    Then the order-producer pauses its submissions but observes pending sagas for 2 seconds
    When the SQL confirmer resumes
    Then the order-producer observer drains the backlog while submissions remain paused
    And the order-producer shows PAUSED within 10 seconds and sends no request for 2 seconds
    When the order-producer resumes
    Then the order-producer starts 0.7 to 1.1 new orders per second
    When the order-producer is paused
    Then the order-producer observer drains the backlog while submissions remain paused
    And the run "control-acceptance" has consecutive ordinals from 0 with one allocation each
    And the product-reader shows revision 0, unpaused
    And the product-reader sends 15 to 22 "reads" requests per second
    And the order-producer counts startup, allocation, submission and polling requests separately

  Scenario: The interactive seeder starts one paced run at a time, and repeated runs recover the same catalog
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries
    When the interactive seeder starts with an initial rate of 50
    Then the product-seeder control shows IDLE at a target of 50 "HTTP requests/s" within 60 seconds
    And the product-seeder sends no request for 2 seconds
    When a seed run starts
    Then a second seed run is rejected with 409 RUN_ACTIVE
    And the product-seeder sends 40 to 52 "all" requests per second
    When the product-seeder is paused
    Then the product-seeder shows PAUSED within 10 seconds and sends no request for 2 seconds
    When the product-seeder target rate becomes 1000
    And the product-seeder resumes
    Then the product-seeder shows the concurrency limit 1 below the target
    And the seed run 1 completes within 60 seconds after 1056 operations without errors
    And the shards store 151 sellers and 201 products of catalog-v2 with their dataset payloads and placement
    And the seed run summary has the digest of the stored IDs
    When a seed run starts
    Then the seed run 2 completes within 60 seconds after 1056 operations without errors
    And the seed run summary has the digest of the stored IDs
    And the stored catalog equals the catalog after the first seed run
    When the interactive seeder restarts with an initial rate of 50
    Then the product-seeder control shows IDLE at a target of 50 "HTTP requests/s" within 60 seconds
    And the product-seeder shows revision 0, unpaused
