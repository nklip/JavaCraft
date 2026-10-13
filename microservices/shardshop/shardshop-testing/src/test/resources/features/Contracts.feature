Feature: Contract examples and workload datasets across modules
  Product, order and ledger each check their own contract, its examples and its boundaries in a unit test. These
  scenarios check what connects the contracts of different modules: the examples describe one EUR purchase, and each
  workload dataset row is a valid creation request of its provider. They read the contracts and the dataset TSV files
  from the source tree and start no application or container.

  Scenario: The RecordOrder example buys the product of the product creation example
    Then each snapshot item of the RecordOrder example has the IDs, name, price and currency of the product creation example

  Scenario: Both ledger result examples answer the RecordOrder example
    Then each ledger result example has the command, saga, order, buyer and fingerprint of the RecordOrder example
    And each ledger result example uses the result message ID that the RecordOrder example reserved
    And each ledger result example has the amount and currency of the RecordOrder snapshot
    And each ledger result example was decided at or after the time of the RecordOrder example

  Scenario: The illustrative IDs of all contract examples follow the service-issued ID rules
    Then the seller, product and buyer IDs of all contract examples follow the service-issued ID rules

  Scenario: Each workload dataset row is a valid creation request of its provider
    Then the 150 sellers of catalog-v1 match the "CreateSeller" schema of the product contract
    And the 300 products of catalog-v1 match the "CreateProduct" schema of the product contract
    And the 3000 buyers of buyers-v1 match the "BuyerCreation" schema of the order contract
