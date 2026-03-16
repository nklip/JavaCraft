Feature: test UserActivityController

  Scenario: prepare data
    Given index 'user-activity' exists

  # 20 posts, 300 unique users (upvotes + downvotes per row = user budget).
  # All events are ingested in parallel to simulate concurrent real-world traffic.
  # Posts 01-11 have a positive net score -> appear in Hot ranking.
  # Posts 12-20 are neutral or net-negative -> excluded by the decay filter.
  # The ordering validates that quality ratio beats raw volume:
  #   post-05 (net 12, 20 votes) ranks above post-04 (net 11, 25 votes).
  Scenario: Hot posts
    Given all user-activity events are deleted
    When users vote on posts in parallel
      | postId  | up | down |
      | post-01 | 30 |    2 |
      | post-02 | 24 |    6 |
      | post-03 | 20 |    5 |
      | post-04 | 18 |    7 |
      | post-05 | 16 |    4 |
      | post-06 | 14 |    6 |
      | post-07 | 12 |    3 |
      | post-08 | 11 |    5 |
      | post-09 |  9 |    6 |
      | post-10 |  8 |    4 |
      | post-11 |  7 |    5 |
      | post-12 |  5 |    5 |
      | post-13 |  4 |    6 |
      | post-14 |  3 |    7 |
      | post-15 |  2 |    8 |
      | post-16 |  1 |    5 |
      | post-17 |  1 |    5 |
      | post-18 |  1 |    6 |
      | post-19 |  1 |    7 |
      | post-20 |  1 |   10 |
    Then hot posts are returned in this order
      | post-01 |
      | post-02 |
      | post-03 |
      | post-05 |
      | post-04 |
      | post-07 |
      | post-06 |
      | post-08 |
      | post-10 |
      | post-09 |
      | post-11 |

  # 20 posts, 300 unique users. Ranking = raw upvote count DESC; downvotes are ignored.
  # Four user-behaviour archetypes encoded in the data:
  #   Controversial (posts 01-03): many upvotes AND many downvotes — ranks high in Top,
  #     but would rank low in Hot, proving Top ≠ quality ranking.
  #   Liked (posts 04-10): upvotes > downvotes, standard positive content.
  #   Pure-positive (posts 11-16): zero downvotes, niche — quality wins but lower volume.
  #   Neutral (posts 17-20): upvotes = downvotes — still visible in Top (downvotes ignored).
  Scenario: Top posts
    Given all user-activity events are deleted
    When users vote on posts in parallel
      | postId  | up | down |
      | post-01 | 20 |   14 |
      | post-02 | 19 |   14 |
      | post-03 | 18 |   10 |
      | post-04 | 17 |    8 |
      | post-05 | 16 |    4 |
      | post-06 | 15 |    5 |
      | post-07 | 14 |    6 |
      | post-08 | 13 |    7 |
      | post-09 | 12 |    8 |
      | post-10 | 11 |    4 |
      | post-11 | 10 |    0 |
      | post-12 |  9 |    0 |
      | post-13 |  8 |    0 |
      | post-14 |  7 |    0 |
      | post-15 |  6 |    0 |
      | post-16 |  5 |    0 |
      | post-17 |  4 |    4 |
      | post-18 |  3 |    3 |
      | post-19 |  2 |    2 |
      | post-20 |  1 |    1 |
    Then top posts are returned in this order
      | post-01 |
      | post-02 |
      | post-03 |
      | post-04 |
      | post-05 |
      | post-06 |
      | post-07 |
      | post-08 |
      | post-09 |
      | post-10 |
      | post-11 |
      | post-12 |
      | post-13 |
      | post-14 |
      | post-15 |
      | post-16 |
      | post-17 |
      | post-18 |
      | post-19 |
      | post-20 |
