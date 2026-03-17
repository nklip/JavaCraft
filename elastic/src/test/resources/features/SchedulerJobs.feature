Feature: test Scheduler jobs

  Scenario: prepare data
    Given index 'user-activity' is recreated

  Scenario: nothing to remove
    Given index 'user-activity' is recreated
    Then execute cleanup job with expected result of 0

  Scenario: remove 10 old documents
    Given there are 10 outdated records
    Then execute cleanup job with expected result of 10