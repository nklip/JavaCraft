@kind-drill @kind-retry-drill
Feature: Explicit retry exhaustion and dead-letter capacity recovery
  The pinned broker and its source queues stay running throughout each drill.
  The shared drill guard restores workload controls, database fences, replicas,
  queue bindings and policies. Recovery uses durable application state.

  @kind-retry-recovery
  Scenario: A shard outage outlasts broker retries and reconciliation recovers the parked result
    Then a shard outage parks its result and reconciliation recovers the order exactly once

  @kind-dlq-capacity
  Scenario Outline: The <mode> DLQ backpressures <queue> and its application outbox recovers
    Then a "<mode>" DLQ retains source pressure on "<queue>" until its application outbox recovers

    @kind-dlq-unbound
    Examples: Missing parking binding
      | mode    | queue                |
      | unbound | ledger.record-order  |
      | unbound | order.ledger-results |

    @kind-dlq-full
    Examples: Full parking queue
      | mode    | queue                |
      | full    | ledger.record-order  |
      | full    | order.ledger-results |
