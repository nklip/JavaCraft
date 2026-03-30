Feature: SmartService

  Rule: Sync smart service behavior

    @Sync
    Scenario: test a single user with incremental metric updates
      Given we start WireMock server
      When user "1" deletes all previous smart metrics
      Then user "1" has no latest smart metrics
      And user "1" has smart metrics list size "0"
      When user "1" puts smart metrics
        | type | meterId | reading  | date       |
        | gas  | 200     | 2531.111 | 2023-07-28 |
        | ele  | 100     | 674.444  | 2023-07-28 |
      Then user "1" gets latest smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 23 | 200     | 2531.111 | 2023-07-28 |
        | ele  | 13 | 100     | 674.444  | 2023-07-28 |
      And user "1" has smart metrics list size "1"
      When user "1" puts smart metrics
        | type | meterId | reading  | date       |
        | gas  | 200     | 2537.777 | 2023-07-29 |
        | ele  | 100     | 678.888  | 2023-07-29 |
      Then user "1" gets latest smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 24 | 200     | 2537.777 | 2023-07-29 |
        | ele  | 14 | 100     | 678.888  | 2023-07-29 |
      And user "1" has smart metrics list size "2"

    @Sync
    Scenario: test user isolation with three metrics per user
      Given we start WireMock server
      When user "1" deletes all previous smart metrics
      Then user "1" has no latest smart metrics
      And user "1" has smart metrics list size "0"
      When user "1" puts smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 23 | 200     | 2531.111 | 2023-07-28 |
        | ele  | 13 | 100     | 674.444  | 2023-07-28 |
        | gas  | 24 | 200     | 2537.777 | 2023-07-29 |
        | ele  | 14 | 100     | 678.888  | 2023-07-29 |
        | gas  | 25 | 200     | 2600.001 | 2023-07-30 |
        | ele  | 15 | 100     | 699.111  | 2023-07-30 |
      Then user "1" gets latest smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 25 | 200     | 2600.001 | 2023-07-30 |
        | ele  | 15 | 100     | 699.111  | 2023-07-30 |
      And user "1" has smart metrics list size "3"

      When user "2" deletes all previous smart metrics
      Then user "2" has no latest smart metrics
      And user "2" has smart metrics list size "0"
      When user "2" puts smart metrics
        | type | id | meterId | reading | date       |
        | gas  | 31 | 300     | 100.111 | 2024-02-10 |
        | ele  | 41 | 400     | 10.250  | 2024-02-10 |
        | gas  | 32 | 300     | 101.222 | 2024-02-11 |
        | ele  | 42 | 400     | 10.750  | 2024-02-11 |
        | gas  | 33 | 300     | 105.555 | 2024-02-12 |
        | ele  | 43 | 400     | 11.900  | 2024-02-12 |
      Then user "2" gets latest smart metrics
        | type | id | meterId | reading | date       |
        | gas  | 33 | 300     | 105.555 | 2024-02-12 |
        | ele  | 43 | 400     | 11.900  | 2024-02-12 |
      And user "2" has smart metrics list size "3"
      And user "1" gets latest smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 25 | 200     | 2600.001 | 2023-07-30 |
        | ele  | 15 | 100     | 699.111  | 2023-07-30 |
      And user "1" has smart metrics list size "3"

  Rule: Async smart service behavior

    @Async
    Scenario: async smart metric update is accepted immediately and completed via sync polling
      Given we start WireMock server
      When user "3" deletes all previous smart metrics
      Then user "3" has no latest smart metrics
      And user "3" has smart metrics list size "0"
      When user "3" asynchronously puts smart metrics
        | type | meterId | reading  | date       |
        | gas  | 500     | 3333.111 | 2024-03-01 |
        | ele  | 600     | 444.222  | 2024-03-01 |
      Then user receives an async smart tracking id
      When user synchronously polls the last async smart request
      Then last async smart request is still pending
      When user synchronously polls the last async smart request
      Then last async smart request is completed with result "true"
      And user "3" gets latest smart metrics
        | type | id | meterId | reading  | date       |
        | gas  | 53 | 500     | 3333.111 | 2024-03-01 |
        | ele  | 63 | 600     | 444.222  | 2024-03-01 |
      And user "3" has smart metrics list size "1"

    @Async
    Scenario: async smart metric update can be polled synchronously until failure
      Given we start WireMock server
      When user "4" deletes all previous smart metrics
      Then user "4" has no latest smart metrics
      And user "4" has smart metrics list size "0"
      When user "4" asynchronously puts smart metrics
        | type | meterId | reading  | date       |
        | gas  | 700     | 5555.111 | 2024-04-01 |
        | ele  | 800     | 666.222  | 2024-04-01 |
      Then user receives an async smart tracking id
      When user synchronously polls the last async smart request
      Then last async smart request fails with description "Async smart request failed"
      And user "4" has no latest smart metrics
      And user "4" has smart metrics list size "0"
