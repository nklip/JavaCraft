Feature: EKS durability, broker quorum and runtime secrets
  These scenarios run only on the EKS target of infra/terraform/README.md. kind has no S3
  backups, one broker and the Secrets of cluster-create.sh.
  The restore drill restores each database cluster unless shardshop.kind.drillCluster names one.

  @eks-backup
  Scenario: Every shard and the ledger archive WAL continuously and have a completed base backup in S3
    Then every database cluster archives WAL and has a completed base backup

  @eks-pitr-drill
  Scenario: A point-in-time restore returns exactly the rows committed before the target time
    Then a point-in-time restore of each selected database cluster returns only the rows before its target time

  @eks-broker-quorum
  Scenario: Every quorum queue has three members on three brokers and keeps a leader when one broker stops
    Then every quorum queue has three members on three brokers and keeps a leader when one broker stops

  @eks-runtime-secrets
  Scenario: Every database and broker credential comes from AWS Secrets Manager
    Then every runtime Secret is synced from AWS Secrets Manager and every login role is applied
