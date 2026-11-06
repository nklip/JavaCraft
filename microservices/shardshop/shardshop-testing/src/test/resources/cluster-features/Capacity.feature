@cluster-order-capacity
Feature: Sustained order capacity
  Only its own tag selects this check. It holds the drill lock, pauses the producer
  until all saga work drains, waits at most 300 seconds for the replay of a
  restarted producer, holds one target rate,
  then pauses the producer again and restores its controls. A run needs at least
  400 units of each catalog product, thus it causes no stock cancellation.
  A database backlog fails the check when the mean of its last three samples is
  more than 100 above the mean of its first three samples.
  The report gives the saga phases from the database timestamps, the status polls,
  the pod placement and the CPU of the nodes and pods.

  Scenario: The producer sustains 30 new orders/s with bounded backlogs
    Then the producer sustains 30 new orders per second for 90 seconds with at least 29.35 per second
