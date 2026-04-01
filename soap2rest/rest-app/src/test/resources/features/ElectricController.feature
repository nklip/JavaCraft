Feature: ElectricResource

  Rule: Sync electric resource behavior

    @Sync
    Scenario: single account with incremental electric updates
      Given the account 1 doesn't have electric metrics
      Then account 1 has no latest electric metric
      And account 1 has electric metrics list size 0
      When an account 1 submits a PUT request with a new electric reading: 200, 1777.777, '2023-07-15'
      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 1777.777
      And account 1 has electric metrics list size 1
      When an account 1 submits a PUT request with a new electric reading: 200, 1787.777, '2023-07-16'
      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 1787.777
      And account 1 has electric metrics list size 2

    @Sync
    Scenario: accounts and meters isolation with three electric metrics per meter
      Given the account 1 doesn't have electric metrics
      Then account 1 has no latest electric metric
      And account 1 has electric metrics list size 0
      When an account 1 submits electric metrics
        | id | meterId | reading | date       |
        | 13 | 100     | 678.439 | 2023-07-28 |
        | 14 | 100     | 700.111 | 2023-07-29 |
        | 15 | 100     | 720.333 | 2023-07-30 |
      Then check the latest electric reading for the account = 1 and meterId = 100 is equal = 720.333

      Given the account 2 doesn't have electric metrics
      Then account 2 has no latest electric metric
      And account 2 has electric metrics list size 0
      When an account 2 submits electric metrics
        | id | meterId | reading | date       |
        | 21 | 300     | 54.321  | 2024-01-15 |
        | 22 | 300     | 60.999  | 2024-01-16 |
        | 23 | 300     | 61.222  | 2024-01-17 |
      Then check the latest electric reading for the account = 2 and meterId = 300 is equal = 61.222
      And account 2 has electric metrics list size 3

      Then check the latest electric reading for the account = 1 and meterId = 100 is equal = 720.333
      And account 1 has electric metrics list size 3

    @Sync
    Scenario: same date is allowed for different electric meters
      Given the account 1 doesn't have electric metrics
      And account 1 has electric metrics list size 0
      When an account 1 submits a PUT request with a new electric reading: 100, 1000.100, '2024-02-01'
      And an account 1 submits a PUT request with a new electric reading: 200, 2000.200, '2024-02-01'
      Then check the latest electric reading for the account = 1 and meterId = 100 is equal = 1000.100
      And check the latest electric reading for the account = 1 and meterId = 200 is equal = 2000.200
      And account 1 has electric metrics list size 2
