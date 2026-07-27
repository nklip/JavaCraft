# COS226

https://www.cs.princeton.edu/courses/archive/fall24/cos226/

## Syllabus
Description. This course surveys the most important algorithms and data structures in use on computers today. Particular emphasis is given to algorithms for sorting, searching, and graphs. The course concentrates on developing implementations, understanding their performance characteristics, and estimating their potential effectiveness in applications.

## Common Examples of Big O Notation

<table>
<tr>
<td><b>O(1)</b></td><td>Constant Time</td>
</tr>
<tr>
<td><b>O(log n)</b></td><td>Logarithmic Time</td>
</tr>
<tr>
<td><b>O(n)</b></td><td>Linear Time</td>
</tr>
<tr>
<td><b>O(n log n)</b></td><td>Linearithmic Time</td>
</tr>
<tr>
<td><b>O(n^2)</b></td><td>Quadratic Time</td>
</tr>
<tr>
<td><b>O(2^n)</b></td><td>Exponential Time</td>
</tr>
<tr>
<td><b>O(n!)</b></td><td>Factorial Time</td>
</tr>
</table>

## Running the tests

Run these tests with Maven, not with the green arrow in IntelliJ IDEA.

```bash
# one module
mvn -pl algs/cos226/part1/8Puzzle -am test
```

The `-am` option also builds `cos226/cos226-support` module, which contains the shared resource-file locator
used by assignments in both parts.

IntelliJ's test runner does not go through Maven, so it ignores the surefire configuration in
the POMs and enables assertions on its own. algs4's `MinPQ` runs `assert isMinHeap()` after every
`insert` and `delMin`, and that check is an O(n) scan, so any search built on `MinPQ` becomes
quadratic when assertions are on. In `part1/8Puzzle`, `puzzle31.txt` goes from 52 ms to 136 s.

It also changes the answers, not just the timing: `Solver.tryToSolve` switches to a different
comparator once a solve passes 20 seconds, and that comparator is not admissible, so A* stops
returning the shortest solution. `puzzle44.txt` then reports 46 moves instead of 44, and the
result depends on how fast the machine is.

`part1/8Puzzle/pom.xml` sets `<enableAssertions>false</enableAssertions>` for surefire, which is
why the Maven run is fast and reproducible.
