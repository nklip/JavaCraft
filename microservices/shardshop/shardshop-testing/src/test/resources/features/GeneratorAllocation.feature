Feature: Generator IDs that product reserves in its own JVM
  Product reserves a new generator ID at each JVM start through the Kubernetes API, with Apache HttpClient 5, the
  token of its service account, only the CA of the API server and a check of the server host name. Each scenario has
  its own unprivileged k3s API server with the namespace shardshop and the generator RBAC and retention policy of the
  chart, which the integration build renders with helm template. The registry ConfigMap starts as the initialization
  script creates it.

  Background:
    Given a Kubernetes API with the generator RBAC and retention policy of the chart

  Scenario: Each start of product reserves the next ID, and the sellers that product creates carry that ID
    Given the generator registry at high-water 0
    And the catalog shards "shard-a=US" that Flyway migrated
    When product runs as the service account "shardshop-product"
    Then product logged that it reserved the generator ID 1
    And a new seller of product has the generator ID 1
    When product restarts
    Then product logged that it reserved the generator ID 2
    And a new seller of product has the generator ID 2
    And the registry high-water is 2

  Scenario: Concurrent starts of product reserve distinct IDs
    Given the generator registry at high-water 0
    When 6 product JVMs check their startup at the same time as the service account "shardshop-product"
    Then the JVMs logged the distinct generator IDs 1 to 6, and no warning or error
    And the registry high-water is 6

  Scenario: Only the product and order service accounts can read and advance the registry, and nothing can lower it
    Given the generator registry at high-water 5
    Then the service accounts have exactly these permissions on ConfigMaps in the namespace shardshop:
      | account                  | get registry | patch registry | update registry | delete registry | get identity | patch identity | get routing | patch routing | list configmaps | watch configmaps | create configmaps |
      | shardshop-product        | yes          | yes            | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | shardshop-order          | yes          | yes            | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | default                  | no           | no             | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | shardshop-ledger         | no           | no             | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | shardshop-product-seeder | no           | no             | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | shardshop-product-reader | no           | no             | no              | no              | no           | no             | no          | no            | no              | no               | no                |
      | shardshop-order-producer | no           | no             | no              | no              | no           | no             | no          | no            | no              | no               | no                |
    And the retention policy rejects these changes of the registry:
      | the high-water 4    |
      | the high-water 5    |
      | the high-water 1024 |
      | a deletion          |
    And the registry high-water is 5

  Scenario: A registry with another identity stops the start before a reservation
    Given the generator registry at high-water 0
    When product starts as the service account "shardshop-product" with the UID of another registry
    Then product stops with "Generator registry is stale or has been replaced" and reserves no ID
    And the registry high-water is 0

  Scenario: An exhausted registry stops the start
    Given the generator registry at high-water 1023
    When product starts as the service account "shardshop-product"
    Then product stops with "Generator IDs exhausted" and reserves no ID
    And the registry high-water is 1023

  Scenario: A missing registry stops the start
    When product starts as the service account "shardshop-product" without a registry
    Then product stops with "Generator registry could not be read" and reserves no ID

  Scenario: A service account without access to the registry cannot start product
    Given the generator registry at high-water 0
    When product starts as the service account "default"
    Then product stops with "Generator registry could not be read" and reserves no ID
    And the registry high-water is 0

  Scenario: A service account that can only read the registry cannot start product
    Given the generator registry at high-water 0
    And the service account "registry-reader" may only get the registry
    When product starts as the service account "registry-reader"
    Then product stops with "Generator registry could not be updated" and reserves no ID
    And the registry high-water is 0

  Scenario: Product trusts only the CA of the API server certificate
    Given the generator registry at high-water 0
    When product starts as the service account "shardshop-product" and trusts the client CA instead of the server CA
    Then product stops with "Generator registry could not be read" and reserves no ID
    And the registry high-water is 0

  Scenario: Product accepts the API server certificate only for a host name that the certificate names
    Given the generator registry at high-water 0
    When product starts as the service account "shardshop-product" with a host name that the server certificate does not name
    Then product stops with "Generator registry could not be read" and reserves no ID
    And the registry high-water is 0

  Scenario: An API server that does not answer stops the start after the request timeout
    Given the generator registry at high-water 0
    When product starts as the service account "shardshop-product" while the API server does not answer
    Then product stops with "Generator registry could not be read" within 20 seconds and reserves no ID
