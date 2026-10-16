Feature: Kind lab lifecycle infrastructure gates
  These scenarios consume one invocation's immutable rendered configuration.
  The kind Maven profile opts in to live checks and selects the required gate.

  @kind-config
  Scenario: Supported kind creation configuration
    Then the kind creation configuration is supported

  @kind-nodes
  Scenario: Retained nodes match the immutable creation configuration
    Then the retained kind nodes match the creation configuration

  @kind-retention
  Scenario: Reconciliation preserves retained data ownership and generator state
    Then the retained topology and allocator permit infrastructure reconciliation

  @kind-admission-bootstrap
  Scenario: Fresh generator initialization is protected by admission
    Then the generator admission policy protects fresh initialization

  @kind-allocator
  Scenario: Retained generator allocation is protected by admission
    Then the retained generator state and admission policy are valid

  @kind-dns
  Scenario: DNS works across all nodes after node startup
    Then cluster DNS resolves from every kind node

  @kind-infrastructure
  Scenario: Infrastructure has reconciled and serves the required database topology
    Then the databases have reconciled and serve the required topology

  @kind-infrastructure
  Scenario: RabbitMQ has reconciled and serves the durable retry and parking topology
    Then the RabbitMQ broker is ready with the required durable delivery topology
