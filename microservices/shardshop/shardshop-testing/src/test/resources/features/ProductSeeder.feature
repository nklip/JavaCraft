@product-seeder
Feature: Product seeder against product and PostgreSQL shards
  The packaged seeder sends catalog-v2 to the packaged product. The expected payloads come from the dataset TSV files,
  independently of the Java definitions. Each seeder run is a new JVM without local state, as each Job pod is.
  After each scenario, a hook stops product and checks that no product JVM logged a warning or an error.

  Background:
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries

  Scenario: Seeding recovers the same service-issued IDs after a failed run, and completes only after its verification
    Given a database session holds the creation lock of the seller "catalog-v2.EU.seller.10"
    When the seeder runs with 2 attempts and a retry backoff of 100ms
    Then the seeder exits with code 1 and logs only the error "Catalog seeding failed: Create seller catalog-v2.EU.seller.10 failed on attempt 2: HTTP 503 CATALOG_UNAVAILABLE"
    And the shards store 59 sellers and 68 products of catalog-v2 with their dataset payloads and placement
    When the database session releases the lock
    And product restarts with a new generator ID
    And the seeder runs
    Then the seeder exits with code 0 and logs the summary "US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, KRW=4, JPY=14, VND=1}"
    And the shards store 151 sellers and 201 products of catalog-v2 with their dataset payloads and placement
    And the logged key-to-ID digest is the digest of the stored IDs
    And the entities of the failed run did not change
    When the seeder runs
    Then the seeder exits with code 0 and logs the same key-to-ID digest
    And the stored catalog did not change
    When product restarts with the replica read profile and replica endpoints that refuse connections
    And the seeder runs with 2 attempts and a retry backoff of 100ms
    Then the seeder exits with code 1 and logs only the error "Catalog seeding failed: Read seller catalog-v2.US.seller.1 failed on attempt 2: HTTP 503 READ_REPLICA_UNAVAILABLE"
    And the stored catalog did not change
