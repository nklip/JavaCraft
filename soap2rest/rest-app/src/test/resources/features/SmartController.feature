Feature: SmartResource

  Rule: Sync smart resource behavior

    @Sync
    Scenario: single account with incremental smart updates
      Given the account 1 doesn't have any metrics
      Then account 1 has no latest electric metric
      And account 1 has no latest gas metric
      And account 1 has electric metrics list size 0
      And account 1 has gas metrics list size 0
      When an account 1 submits a PUT request with new metrics
        | type | meterId | reading  | date       |
        | gas  | 100     | 686.666  | 2023-07-17 |
        | ele  | 200     | 2345.505 | 2023-07-17 |
      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 2345.505
      And check the latest gas reading for the account = 1 and meterId = 100 is equal = 686.666
      And account 1 has electric metrics list size 1
      And account 1 has gas metrics list size 1
      When an account 1 submits a PUT request with new metrics
        | type | meterId | reading  | date       |
        | gas  | 100     | 700.502  | 2023-07-20 |
        | ele  | 200     | 2536.708 | 2023-07-20 |
      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 2536.708
      And check the latest gas reading for the account = 1 and meterId = 100 is equal = 700.502
      And account 1 has electric metrics list size 2
      And account 1 has gas metrics list size 2

    @Sync
    Scenario: accounts and meters isolation with three smart metrics per meter
      Given the account 1 doesn't have any metrics
      Then account 1 has no latest electric metric
      And account 1 has no latest gas metric
      And account 1 has electric metrics list size 0
      And account 1 has gas metrics list size 0
      When an account 1 submits a PUT request with new metrics
        | type | meterId | reading  | date       |
        | gas  | 100     | 678.439  | 2023-07-28 |
        | gas  | 100     | 700.111  | 2023-07-29 |
        | gas  | 100     | 720.333  | 2023-07-30 |
        | ele  | 200     | 1788.111 | 2023-07-28 |
        | ele  | 200     | 1799.222 | 2023-07-29 |
        | ele  | 200     | 1801.333 | 2023-07-30 |
      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 1801.333
      And check the latest gas reading for the account = 1 and meterId = 100 is equal = 720.333
      And account 1 has electric metrics list size 3
      And account 1 has gas metrics list size 3

      Given the account 2 doesn't have any metrics
      Then account 2 has no latest electric metric
      And account 2 has no latest gas metric
      And account 2 has electric metrics list size 0
      And account 2 has gas metrics list size 0
      When an account 2 submits a PUT request with new metrics
        | type | meterId | reading | date       |
        | gas  | 300     | 54.321  | 2024-01-15 |
        | gas  | 300     | 60.999  | 2024-01-16 |
        | gas  | 300     | 61.222  | 2024-01-17 |
        | ele  | 400     | 954.321 | 2024-01-15 |
        | ele  | 400     | 960.999 | 2024-01-16 |
        | ele  | 400     | 961.222 | 2024-01-17 |
      Then check the latest electric reading for the account = 2 and meterId = 400 is equal = 961.222
      And check the latest gas reading for the account = 2 and meterId = 300 is equal = 61.222
      And account 2 has electric metrics list size 3
      And account 2 has gas metrics list size 3

      Then check the latest electric reading for the account = 1 and meterId = 200 is equal = 1801.333
      And check the latest gas reading for the account = 1 and meterId = 100 is equal = 720.333
      And account 1 has electric metrics list size 3
      And account 1 has gas metrics list size 3

  Rule: Async smart resource behavior

    @Async
    Scenario: async smart update completes successfully
      Given the account 1 doesn't have any metrics
      And account 1 has no latest electric metric
      And account 1 has no latest gas metric
      When an account 1 submits an ASYNC PUT request with new metrics
        | type | meterId | reading  | date       |
        | gas  | 100     | 686.666  | 2023-07-17 |
        | ele  | 200     | 2345.505 | 2023-07-17 |
      Then polling the async smart request eventually returns COMPLETED
      And check the latest electric reading for the account = 1 and meterId = 200 is equal = 2345.505
      And check the latest gas reading for the account = 1 and meterId = 100 is equal = 686.666
      And account 1 has electric metrics list size 1
      And account 1 has gas metrics list size 1

    # this test also validates successfulness of rolling back electric changes
    @Async
    Scenario: async smart update fails for invalid meter
      Given the account 1 doesn't have any metrics
      And account 1 has no latest electric metric
      And account 1 has no latest gas metric
      When an account 1 submits an ASYNC PUT request with new metrics
        | type | meterId | reading | date        |
        | gas  | 100     | 686.666  | 2023-07-17 |
        | ele  | 999     | 2345.505 | 2023-07-17 |
      Then polling the async smart request eventually returns FAILED with message "Meter is not linked to account."
      And account 1 has no latest electric metric
      And account 1 has no latest gas metric
      And account 1 has electric metrics list size 0
      And account 1 has gas metrics list size 0
