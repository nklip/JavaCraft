Feature: test UserActivityController

  Scenario: prepare data
    Given index 'user-activity' exists

  # Shared baseline ingestion for ranking tests.
  # 50 unique users, 20 unique posts, sparse upvote/downvote activity.
  # Events are spread across ~6 months so Top and Hot can diverge later.
  Scenario: baseline ingestion for top and hot
    Given all user-activity events are deleted
    When reddit baseline activity is ingested for top and hot comparison
    Then baseline ingestion has 50 unique users and 20 unique posts

  Scenario: Hot posts
    Given reddit baseline activity was ingested
    Then hot posts endpoint returns ranked results

  Scenario: Top posts
    Given reddit baseline activity was ingested
    Then top posts endpoint returns ranked results
