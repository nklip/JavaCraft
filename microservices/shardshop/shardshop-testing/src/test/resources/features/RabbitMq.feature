@rabbitmq
Feature: RabbitMQ provisioning and delivery reliability
  The one-broker lab uses the production topology, startup command and generated
  Secret definitions. An arbitrary non-image UID runs with a read-only root
  filesystem and retained data, matching the deployment security contract.

  Background:
    Given RabbitMQ runs with the chart configuration on retained storage

  Scenario: Effective topology, feature flags, runtime and least privilege are ready
    Then RabbitMQ exposes the pinned runtime, effective topology and readiness contract
    And RabbitMQ rejects guest and restricts order and ledger to their own messaging operations

  Scenario Outline: Native failed retries preserve their bounded budget
    Then native reject retries "<queue>" at the configured delays and delivery limit while nack does not count as failure

    Examples:
      | queue                |
      | ledger.record-order  |
      | order.ledger-results |

  Scenario Outline: Persistent deliveries and topology survive replacement on the same storage
    Then persistent unacknowledged and parked messages in "<queue>" survive broker container replacement

    Examples:
      | queue                |
      | ledger.record-order  |
      | order.ledger-results |

  Scenario: Capacity backpressure preserves an unconfirmed dead letter
    Then queue capacity rejects publishes and a full parking queue retains its dead letter until capacity returns
