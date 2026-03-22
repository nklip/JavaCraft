Feature: test VoteController

  # Exercises all 9 transitions via POST /api/services/user-votes.
  # Verification reads ES directly (user-votes and posts indices) — no service layer.
  #
  # ┌───┬──────────────┬────────────┬───────────┬───────┬─────────────────────┐
  # │ N │ Prior state  │ New action │ ES Result │ Delta │ Math                │
  # ├───┼──────────────┼────────────┼───────────┼───────┼─────────────────────┤
  # │ 1 │ No document  │ UPVOTE     │ Created   │ +1    │ 0 -> +1             │
  # │ 2 │ No document  │ DOWNVOTE   │ Created   │ -1    │ 0 -> -1             │
  # │ 3 │ UPVOTE       │ UPVOTE     │ NoOp      │ 0     │ no write            │
  # │ 4 │ DOWNVOTE     │ DOWNVOTE   │ NoOp      │ 0     │ no write            │
  # │ 5 │ DOWNVOTE     │ UPVOTE     │ Updated   │ +2    │ -1 -> +1 = net +2   │
  # │ 6 │ UPVOTE       │ DOWNVOTE   │ Updated   │ -2    │ +1 -> -1 = net -2   │
  # │ 7 │ was UPVOTE   │ NOVOTE     │ Deleted   │ -1    │ +1 -> 0 = net -1    │
  # │ 8 │ was DOWNVOTE │ NOVOTE     │ Deleted   │ +1    │ -1 -> 0 = net +1    │
  # │ 9 │ No document  │ NOVOTE     │ NotFound  │ 0     │ nothing to undo     │
  # └───┴──────────────┴────────────┴───────────┴───────┴─────────────────────┘

  Background:
    Given index 'posts' is recreated
    Given index 'user-votes' is recreated

  Scenario: 1) first UPVOTE creates a vote document and increments karma by 1
    Given post 'post-01' exists in 'posts' index with author 'user-001'
    When user 'user-001' sends UPVOTE on post 'post-01'
    Then the vote response result is 'Created'
    And a vote exists for user 'user-001' on post 'post-01' with action 'UPVOTE'
    And post 'post-01' karma is 1

  Scenario: 2) first DOWNVOTE creates a vote document and decrements karma by 1
    Given post 'post-02' exists in 'posts' index with author 'user-002'
    When user 'user-002' sends DOWNVOTE on post 'post-02'
    Then the vote response result is 'Created'
    And a vote exists for user 'user-002' on post 'post-02' with action 'DOWNVOTE'
    And post 'post-02' karma is -1

  Scenario: 3) repeat UPVOTE is a no-op and karma stays unchanged
    Given post 'post-03' exists in 'posts' index with author 'user-003'
    When user 'user-003' sends UPVOTE on post 'post-03'
    And post 'post-03' karma is 1
    When user 'user-003' sends UPVOTE on post 'post-03'
    Then the vote response result is 'NoOp'
    And a vote exists for user 'user-003' on post 'post-03' with action 'UPVOTE'
    And post 'post-03' karma is 1

  Scenario: 4) repeat DOWNVOTE is a no-op and karma stays unchanged
    Given post 'post-04' exists in 'posts' index with author 'user-004'
    When user 'user-004' sends DOWNVOTE on post 'post-04'
    And post 'post-04' karma is -1
    When user 'user-004' sends DOWNVOTE on post 'post-04'
    Then the vote response result is 'NoOp'
    And a vote exists for user 'user-004' on post 'post-04' with action 'DOWNVOTE'
    And post 'post-04' karma is -1

  Scenario: 5) UPVOTE after DOWNVOTE updates the vote document and adds 2 to karma
    Given post 'post-05' exists in 'posts' index with author 'user-005'
    When user 'user-005' sends DOWNVOTE on post 'post-05'
    When user 'user-005' sends UPVOTE on post 'post-05'
    Then the vote response result is 'Updated'
    And a vote exists for user 'user-005' on post 'post-05' with action 'UPVOTE'
    And post 'post-05' karma is 1

  Scenario: 6) DOWNVOTE after UPVOTE updates the vote document and subtracts 2 from karma
    Given post 'post-06' exists in 'posts' index with author 'user-006'
    When user 'user-006' sends UPVOTE on post 'post-06'
    When user 'user-006' sends DOWNVOTE on post 'post-06'
    Then the vote response result is 'Updated'
    And a vote exists for user 'user-006' on post 'post-06' with action 'DOWNVOTE'
    And post 'post-06' karma is -1

  Scenario: 7) NOVOTE after UPVOTE deletes the vote document and decrements karma by 1
    Given post 'post-07' exists in 'posts' index with author 'user-007'
    When user 'user-007' sends UPVOTE on post 'post-07'
    When user 'user-007' sends NOVOTE on post 'post-07'
    Then the vote response result is 'Deleted'
    And no vote exists for user 'user-007' on post 'post-07'
    And post 'post-07' karma is 0

  Scenario: 8) NOVOTE after DOWNVOTE deletes the vote document and increments karma by 1
    Given post 'post-08' exists in 'posts' index with author 'user-008'
    When user 'user-008' sends DOWNVOTE on post 'post-08'
    When user 'user-008' sends NOVOTE on post 'post-08'
    Then the vote response result is 'Deleted'
    And no vote exists for user 'user-008' on post 'post-08'
    And post 'post-08' karma is 0

  Scenario: 9) NOVOTE when no prior vote returns NotFound and karma stays unchanged
    Given post 'post-09' exists in 'posts' index with author 'user-009'
    When user 'user-009' sends NOVOTE on post 'post-09'
    Then the vote response result is 'NotFound'
    And no vote exists for user 'user-009' on post 'post-09'
    And post 'post-09' karma is 0
