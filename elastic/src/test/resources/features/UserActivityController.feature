Feature: test UserActivityController

  # ═══════════════════════════════════════════════════════════════════════════════
  # FIXTURE DATA  (src/test/resources/data/csv/)
  # ═══════════════════════════════════════════════════════════════════════════════
  # Five CSV files covering 300 users × 50 posts = 15,000 events total.
  # Each file represents one post-behaviour archetype so that different ranking
  # algorithms produce visibly different results when tested against the same
  # dataset.  Files can be ingested in parallel — they share no post IDs.
  #
  # Columns: userId, postId, action (UPVOTE|DOWNVOTE), date (ISO-8601 UTC)
  #
  # ┌─────────────────────────────┬───────────┬──────┬──────────────────────────────────────────────┐
  # │ File                        │ Posts     │ Up % │ Date pattern                                 │
  # ├─────────────────────────────┼───────────┼──────┼──────────────────────────────────────────────┤
  # │ events-evergreen.csv        │ post-01…10│  80% │ Steady spread across 6 months                │
  # │                             │           │      │ 50 users per monthly window                  │
  # ├─────────────────────────────┼───────────┼──────┼──────────────────────────────────────────────┤
  # │ events-fresh.csv            │ post-11…20│  90% │ All 300 votes within the last 7 days         │
  # │                             │           │      │ Minimal exponential decay                    │
  # ├─────────────────────────────┼───────────┼──────┼──────────────────────────────────────────────┤
  # │ events-viral-faded.csv      │ post-21…30│  83% │ Big spike Nov–Dec 2025, then silence         │
  # │                             │           │      │ Nothing after Feb 14 2026                    │
  # ├─────────────────────────────┼───────────┼──────┼──────────────────────────────────────────────┤
  # │ events-rising.csv           │ post-31…40│  85% │ Slow baseline Sep–Dec, accelerating Jan–Feb, │
  # │                             │           │      │ recent spike Mar 1–15 (users 201-300)        │
  # ├─────────────────────────────┼───────────┼──────┼──────────────────────────────────────────────┤
  # │ events-controversial.csv    │ post-41…50│  50% │ Users 001-150 → always UPVOTE                │
  # │                             │           │      │ Users 151-300 → always DOWNVOTE              │
  # │                             │           │      │ Spread across 6 months                       │
  # └─────────────────────────────┴───────────┴──────┴──────────────────────────────────────────────┘
  #
  # ── EXPECTED RANKING DIFFERENCES ────────────────────────────────────────────
  #
  # Top (pure upvote count, all-time, no decay):
  #   fresh (270/post) > rising (255/post) > viral-faded (242/post)
  #   > evergreen (240/post) >> controversial (150/post)
  #
  # Hot (exponential time-decay, λ = ln(2)/6h, 7-day window):
  #   fresh (no decay) > rising (Mar spike barely decayed)
  #   > evergreen (partial decay, last votes ~1-30 days ago)
  #   > viral-faded (last vote Feb 14, heavily decayed)
  #   ≈ controversial (net score ≈ 0 throughout → near-zero hot score)
  #
  # Rising (recent velocity vs historical baseline):
  #   rising posts rank first — Mar velocity >> Sep-Dec baseline
  #   fresh ranks second — 100% of votes are "recent" with no baseline
  #
  # Best (Wilson score confidence interval):
  #   evergreen ranks above controversial despite fewer raw upvotes —
  #   Wilson score rewards high positive ratio (80%) with large sample
  #   over 50% ratio even with equal sample size
  #
  # New (sort by first-event timestamp DESC):
  #   fresh posts appear at the top (first event within last 7 days)
  #   evergreen/viral/rising posts first event was 6 months ago
  # ════════════════════════════════════════════════════════════════════════════

  Scenario: prepare data
    Given index 'user-activity' exists
    Given data folder 'data/csv' ingested

  # Shared baseline ingestion for ranking tests.
  # 50 unique users, 20 unique posts, sparse upvote/downvote activity.
  # Events are spread across ~6 months so Top and Hot can diverge later.
  Scenario: baseline ingestion for top and hot
    Given all user-activity events are deleted
    When reddit baseline activity is ingested for top and hot comparison
    Then baseline ingestion has 50 unique users and 20 unique posts

  # Hot ranking: exponential time-decay weighted net score.
  # Score = Σ (upvotes − downvotes) × e^(−λ × ageHours), λ = ln(2)/6.
  # Posts with net-negative scores are excluded from results.
  # Expected: most-recent posts with positive net scores rank first.
  Scenario: Hot posts
    Given reddit baseline activity was ingested
    Then hot posts endpoint returns ranked results

  # Top ranking: total UPVOTE count per post, all-time, no time window.
  # Downvotes are completely ignored — only UPVOTE events are counted.
  # Expected: posts with the most absolute upvotes rank first,
  # regardless of how many downvotes they also received.
  Scenario: Top posts
    Given reddit baseline activity was ingested
    Then top posts endpoint returns ranked results
