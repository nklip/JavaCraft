Feature: Argo CD delivery of the applications with their gates on the AWS deployment
  These scenarios run only on the AWS deployment of infra/terraform/README.md. The PostSync gate
  runs the release scenario in the cluster, and the operator can run it again after the sync.

  @eks-release
  Scenario: The current sync runs the pinned images and its own successful seeder attempt
    Then the current Argo CD sync applied each application resource after its gate, runs the pinned images and seeded the catalog

  @eks-application-drill
  Scenario: Resyncs keep the seeder identity, and no sync can skip the gates
    Then resyncs, a held drill lock and syncs without hooks keep the application gates
