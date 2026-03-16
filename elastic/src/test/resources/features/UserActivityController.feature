Feature: test UserActivityController

  Scenario: prepare data
    Given index 'user-activity' exists

#  Scenario: add new events
#    Given user 'nl0000' doesn't have any events
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0000 | 12345      | People     | Nikita        |
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0000 | 12345      | People     | Nikita        |
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0000 | 12345      | People     | Nikita        |
#    Then user 'nl0000' has 3 hit counts for documentId = '12345', searchType = 'People' and pattern = 'Nikita'
#
#  Scenario: test sorting order
#    Given user 'nl0001' doesn't have any events
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0001 | 12345      | People     | Nikita        |
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0001 | 12345      | Company    | Microsoft     |
#    When add new event with expected result = 'Created'
#    # | userId | documentId | searchType | searchPattern |
#      | nl0001 | 12345      | Company    | Microsoft     |
#    Then user 'nl0001' has next sorting results
#    # | Pattern   |
#      | Microsoft |
#      | Nikita    |
#
#  Scenario: test multiple requests in parallel
#    Given user 'nl0002' doesn't have any events
#    When there are 3 requests
#    # | userId | documentId | searchType | searchPattern |
#      | nl0002 | 12345      | People     | Nikita        |
#    Then user 'nl0002' has 3 hit counts for documentId = '12345', searchType = 'People' and pattern = 'Nikita'
