Feature: EWRS work requests

  Scenario: happy path is driven through the scenario module
    When scenario "HAPPY_PATH" is executed
    Then the last scenario response has HTTP 200 and final status "COMPLETED"
    And projected work request "last" eventually has status "COMPLETED"
    And budget projection "PLATFORM-2026" eventually has reserved 40 and remaining 210
    And work request "last" timeline eventually equals statuses "CREATED,ACCEPTED,RUNNING,COMPLETED"

  Scenario: budget denied path is driven through the scenario module
    When scenario "APPROVAL_DENIED" is executed
    Then the last scenario response has HTTP 200 and final status "REJECTED"
    And projected work request "last" eventually has status "REJECTED"
    And budget projection "OPS-2026" eventually has reserved 0 and remaining 120
    And work request "last" timeline eventually equals statuses "CREATED,REJECTED"

  Scenario: explicit reject path is driven through the scenario module
    When scenario "EXPLICIT_REJECT" is executed
    Then the last scenario response has HTTP 200 and final status "REJECTED"
    And projected work request "last" eventually has status "REJECTED"
    And budget projection "RND-2026" eventually has reserved 0 and remaining 180
    And work request "last" timeline eventually equals statuses "CREATED,REJECTED"

  Scenario: invalid transition scenario leaves only the created event
    When scenario "INVALID_START" is executed
    Then the last scenario response has HTTP 200 and final status "CREATED"
    And work request "last" timeline eventually equals statuses "CREATED"

  Scenario: load endpoint generates deterministic mixed traffic
    When load is generated with count 4
    Then the last scenario response has HTTP 200 and 4 runs
    And projected work request list eventually contains 4 items
    And budget projection "PLATFORM-2026" eventually has reserved 40 and remaining 210

  Scenario: replay rebuild reproduces request and budget projections after scenario execution
    When scenario "HAPPY_PATH" is executed
    And scenario "EXPLICIT_REJECT" is executed
    And projections are rebuilt
    Then rebuild response reports 6 events, 2 requests, and 3 budgets
    And projected work request list eventually contains 2 items
    And budget projection "PLATFORM-2026" eventually has reserved 40 and remaining 210
    And budget projection "RND-2026" eventually has reserved 0 and remaining 180
