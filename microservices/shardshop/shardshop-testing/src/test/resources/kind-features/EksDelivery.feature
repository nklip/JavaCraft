Feature: Argo CD delivery of the database layer on the AWS deployment
  These scenarios run only on the AWS deployment of infra/terraform/README.md.
  The drill needs the digests and versions of the two drill charts from the runbook. Its
  migrations stay, thus a second drill on the same databases needs new migration versions.

  @eks-delivery
  Scenario: Argo CD delivers each database layer from a digest-pinned OCI chart
    Then every database layer comes from a digest-pinned OCI chart and is synced, healthy and protected from deletion

  @eks-delivery-drill
  Scenario: The delivery contract survives resyncs, changed, interrupted and failed migrations
    Then resyncs, changed, interrupted and failed migrations keep the delivery contract
