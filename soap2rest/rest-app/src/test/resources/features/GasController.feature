Feature: GasResource

  Rule: Sync gas resource behavior

    @Sync
    Scenario: single account with incremental gas updates
      Given the account 1 doesn't have gas metrics
      Then account 1 has no latest gas metric
      And account 1 has gas metrics list size 0
      When an account 1 submits a PUT request with a new gas reading: 100, 666.666, '2023-07-16'
      Then check the latest gas reading for the account = 1 and meterId = 100 is equal = 666.666
      And account 1 has gas metrics list size 1
      When an account 1 submits a PUT request with a new gas reading: 100, 686.666, '2023-07-17'
      Then check the latest gas reading for the account = 1 and meterId = 100 is equal = 686.666
      And account 1 has gas metrics list size 2

    @Sync
    Scenario: accounts and meters isolation with three gas metrics per meter
      Given the account 1 doesn't have gas metrics
      Then account 1 has no latest gas metric
      And account 1 has gas metrics list size 0
      When an account 1 submits gas metrics
        | id | meterId | reading | date       |
        | 13 | 200     | 234.439 | 2023-07-28 |
        | 14 | 200     | 237.111 | 2023-07-29 |
        | 15 | 200     | 239.333 | 2023-07-30 |
      Then check the latest gas reading for the account = 1 and meterId = 200 is equal = 239.333

      Given the account 2 doesn't have gas metrics
      Then account 2 has no latest gas metric
      And account 2 has gas metrics list size 0
      When an account 2 submits gas metrics
        | id | meterId | reading | date       |
        | 21 | 400     | 456.321  | 2024-01-15 |
        | 22 | 400     | 459.999  | 2024-01-16 |
        | 23 | 400     | 462.222  | 2024-01-17 |
      Then check the latest gas reading for the account = 2 and meterId = 400 is equal = 462.222
      And account 2 has gas metrics list size 3

      Then check the latest gas reading for the account = 1 and meterId = 200 is equal = 239.333
      And account 1 has gas metrics list size 3

    @Sync
    Scenario: same date is allowed for different gas meters
      Given the account 1 doesn't have gas metrics
      And account 1 has gas metrics list size 0
      When an account 1 submits a PUT request with a new gas reading: 100, 300.300, '2024-02-01'
      And an account 1 submits a PUT request with a new gas reading: 200, 400.400, '2024-02-01'
      Then check the latest gas reading for the account = 1 and meterId = 100 is equal = 300.300
      And check the latest gas reading for the account = 1 and meterId = 200 is equal = 400.400
      And account 1 has gas metrics list size 2
