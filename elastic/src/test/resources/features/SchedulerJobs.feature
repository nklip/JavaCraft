Feature: test Scheduler jobs

  Background:
    Given index 'user-votes' is recreated

  Scenario: nothing to remove
    Then execute cleanup job with expected result of 0

  Scenario: remove 10 old documents
    Given there are 10 outdated records
    Then execute cleanup job with expected result of 10