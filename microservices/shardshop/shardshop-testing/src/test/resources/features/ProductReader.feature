@product-reader
Feature: Product reader against product and PostgreSQL shards
  The packaged reader uses its default load and connection policy: 200 requests/s, 16 workers, 16 HTTP/1.1
  connections and a 5-second read deadline. Its reports come every 5 seconds. The reader sees the catalog only through
  the product API. Product keeps its HTTP port when it restarts.
  After each scenario, a hook stops product and checks that no product JVM logged a warning or an error.

  Background:
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated

  Scenario: The reader waits for the complete catalog, then reads the seeded IDs and recovers from product restarts
    Given product runs against the shard primaries
    When the reader starts
    Then the reader waits for the catalog and does not start its load
    When the seeder runs
    Then the seeder exits with code 0 and logs the summary "US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, KRW=4, JPY=14, VND=1}"
    And the reader starts its load within 60 seconds and resolves "US 50 sellers, 50 products; EU 50 sellers, 100 products; ASIA 51 sellers, 51 products" with the digest of the seeder
    And the next 3 reports of the reader have only OK reads at 180 to 201 requests/s in all three regions, 16 open connections and no read that waits for a connection
    When product stops for 6 seconds, then runs with the replica read profile for 6 seconds, and then runs against the shard primaries
    Then the reader records TRANSPORT and REPLICA_UNAVAILABLE reads, and then only OK reads within 30 seconds
    And no read since the product stop has the outcome NOT_FOUND, INVALID_RESPONSE or HTTP_ERROR, or takes longer than 5.1 seconds
    When the reader receives SIGTERM
    Then the reader exits with code 0 or 143, its last report is the total of all reads, and it logs no error
    And the stored catalog did not change

  Scenario: The reader stops with an error when the catalog does not become complete within its maximum wait
    Given product runs with the replica read profile and replica endpoints that refuse connections
    When the reader starts with a maximum wait of 10 seconds and a maximum backoff of 2 seconds
    Then the reader exits with code 1 within 60 seconds and logs only the error of a seller lookup that failed with REPLICA_UNAVAILABLE
    And the reader did not start its load
