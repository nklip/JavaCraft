Feature: PostgreSQL TLS and connection recovery of product
  Product connects to each shard with sslmode=verify-full, as on the lab. A test CA signed the server certificate of
  PostgreSQL for the host name localhost only. These checks are about PostgreSQL TLS, not about HTTPS or a CNPG
  promotion.
  After each scenario, a hook stops product and checks that no product JVM logged a warning or an error.

  Background:
    Given the catalog shards "shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US" that Flyway migrated
    And product runs against the shard primaries
    And the seller "owner" "Synthetic TLS company" in "US" with the key "tls-and-restart"

  Scenario: All PostgreSQL sessions of product use TLS
    Then every PostgreSQL session of product_app uses TLS

  Scenario Outline: Product rejects a server certificate that it cannot verify, within the deadline
    When product restarts with <endpoint>
    Then a GET of the seller "owner" returns 503 CATALOG_UNAVAILABLE in less than 4 seconds

    Examples:
      | endpoint                                                                   |
      | the root certificate of an unrelated CA                                    |
      | the host name 127.0.0.1, which the server certificate does not contain     |

  Scenario: Product reconnects after a PostgreSQL restart, without a restart of product
    When PostgreSQL stops
    Then a GET of the seller "owner" returns 503 CATALOG_UNAVAILABLE in less than 4 seconds
    When PostgreSQL starts again
    Then a GET of the seller "owner" returns the seller within 8 seconds, and each attempt ends in less than 4 seconds
    And the product process is the same process
    And a retry of the creation of the seller "owner" returns 200 with the same seller
    And every PostgreSQL session of product_app uses TLS
