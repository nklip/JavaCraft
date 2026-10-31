# Project Instructions for Coding Agents (Codex)

## Subagent model selection

- Use `gpt-5.6-luna` with `low` reasoning effort for straightforward read-only subtasks. If unavailable, use a suitable available model and note the substitution; if delegation is unavailable, do the work in the main agent.
- For other subtasks, choose the model and reasoning effort according to complexity. Use low reasoning for routine edits; reserve higher reasoning for work that warrants it instead of automatically inheriting `ultra`.
- Default to `fork_turns="none"`. Give each subagent a compact, self-contained assignment with its objective, relevant paths, constraints, and acceptance criteria. Include selected history only when needed; use full history only for dependencies that cannot be captured in the assignment.
- Delegate when independent work can save time or improve quality. Keep trivial lookups and small, cohesive edits with the main agent; batch related inventory questions into one assignment.
- Batch independent searches and file reads. Request relevant excerpts and concise output; reuse established findings instead of rereading unchanged files or logs.
- Ask subagents to return concise findings or changes, supporting file references, validation results, and unresolved questions.
- Define integration boundaries and give editing agents clear file ownership before implementation to reduce conflicting changes.
- Keep synthesis, decisions, and final verification with the main agent. Return ambiguous findings or work requiring deeper reasoning to the main agent.
- Subagents share the parent's investigation and time budgets. Give each question one owner; do not duplicate investigations, builds or test runs across agents. Reuse concise findings instead of restarting the same work.
- The main agent must still read applicable repository instructions and required skills directly.

## 0) Prime directive

- Optimize for: correctness > safety > maintainability > performance.
- Prefer small, reviewable diffs. Avoid broad refactors unless explicitly requested.

## 1) How to work in this repo

- Read the root README, the affected module's README and RUNBOOK.md when present, and the nearest relevant ARCHITECTURE.md when present. Inspect comparable code and tests within that module.
- Keep changes scoped to the requested feature or bugfix.
- Resolve requirements and integration boundaries from the request and repository. Ask early when uncertainty affects correctness, scope, risk or expected effort; use the checkpoints below instead of silently expanding the task.
- Preserve the Java compiler release configured in the applicable POM unless an upgrade is requested; a newer available JDK alone is not a reason to change the target.

### User checkpoints and investigation limits

- Before substantial work, state the intended deliverable, necessary validation and likely expensive phases. Check inexpensive prerequisites first, including required releases, permissions, tools and resource capacity. Do not begin a full build/deployment rehearsal while a known prerequisite prevents completion.
- Report a confirmed blocker immediately: explain the evidence, which acceptance condition it blocks, what is complete and what remains possible. Stop expensive work that depends on it. Ask whether the user wants a bounded partial result or a specific further investigation; continue only small, independent work already within scope while awaiting the answer.
- Ask before materially expanding scope or cost, including turning a focused fix into a broad audit, repairing unrelated host tooling, or recreating a lab repeatedly. Present the concrete proposed action and its expected benefit and cost. Routine implementation choices within the agreed scope do not need repeated confirmation.
- Unless the user agrees otherwise, bound each unplanned investigation to **15 minutes or two hypothesis-driven attempts, whichever comes first**. State the hypothesis, the evidence the next attempt should produce and the stopping condition. At the limit, report findings and ask before continuing; changing agents, commands or conversation turns does not reset the budget.
- Unless a longer phase is already agreed, reach a user decision checkpoint by **30 minutes of elapsed task time** before starting further expensive work. Discuss a required long-running build or suite before launching it if it would exceed that limit. Use observed timings when available and disclose uncertainty rather than inventing an estimate.
- A checkpoint must offer a concrete next step, the remaining uncertainty and a bounded estimate of additional work. Progress narration is not a substitute for asking. Silence is not authorization to expand the investigation; preserve the partial result and report the blocker if no decision arrives.
- Always finish necessary cleanup and restore owned temporary changes before stopping. Budgets must never justify leaving a disruptive experiment running, weakening assertions or describing incomplete qualification as passing.

## 2) Build and validation

- For code or build changes, run relevant tests and required checks from the repository root with explicit module selection. Do not run an unrestricted whole-repository build.
- Unit tests: `mvn -pl <module> test`.
- Example verification suite: `mvn -pl echo/blocking/blocking-verification -am clean verify`.
- Failsafe integration/E2E suites require `verify`; `test` alone does not run them. Select the relevant verification suite using [README.md](README.md#test-coverage) and module documentation.
- Use `-am` when reactor dependencies are needed. Select affected consumers or verification modules when shared contracts change; dependency builds alone do not validate downstream consumers.
- Use the JDK/Maven versions required by the POM and the prerequisites documented for the selected modules. Review-only and prose-only changes do not require Maven unless they affect build behavior.
- Investigate failed validation checks within the limits above; fix failures introduced by the change. Inspect the existing failure evidence before running anything again. Report unrelated failures or environment blockers promptly, continue only bounded independent work, and never describe blocked checks as passing.
- Keep a concise validation record: command, relevant source/configuration/artifact state, outcome and log location. Reuse valid results while that state remains unchanged. A documentation or test-harness edit alone is not a reason to rebuild unchanged application images or repeat their full qualification.
- Before repeating or broadening a build or test, identify the relevant change or new evidence, the specific question the run answers and why a narrower check is insufficient. A previous failure, elapsed time or hope of a clean rerun is not enough. Required regression/reproducibility checks remain valid reasons, but must have an explicit purpose and bounded scope.
- Run the narrowest relevant check first, then required acceptance checks once the candidate is stable. Avoid repeated `clean` builds, full reactors, image rebuilds/scans and lab recreation when existing outputs remain valid and the workflow permits reuse. Preserve failed results even if a later run passes; never weaken required verification to fit a budget.

## 3) Coding standards

- Prefer readability over cleverness (KISS); keep functions small and cohesive.
- Avoid duplication that will likely change together (DRY), without premature abstractions.
- For Java, prefer constructor injection and immutable data structures where practical. Avoid static state unless truly constant.

## 4) Architecture boundaries

- Follow the affected module's documented architecture; infer from existing structure when documentation is absent. Apply the defaults below where appropriate.
- Controllers/handlers own transport mapping, validation, and translation to domain operations.
- Services own business logic; repositories/clients own IO (DB, HTTP, queues), subject to the module's documented boundaries.
- Avoid introducing framework dependencies into pure domain code. Do not refactor existing architecture solely to satisfy these defaults.
- No cross-module imports that violate layering.

## 5) Testing rules (Java)

- Cover new or changed observable behavior, relevant boundaries, errors, and regression risks. Reuse or extend existing tests; each test must identify the requirement, plausible failure, or bug it protects against.
- Derive expectations from contracts, requirements, or independent examples; resolve ambiguity instead of encoding assumptions. Assert meaningful results, state, or errors, without copying implementation logic, checking incidental calls, or adding dummy assertions for unused return values.
- Use coverage to find gaps, with no blanket 100% target or tests solely for lines or trivial accessors. Preserve CI gates and required acceptance checks.
- Bugfixes need a regression test verified to fail for the reported reason before the fix and pass afterward. If automation is impractical, document alternative verification and its limits. TDD is otherwise optional.
- Prefer unit tests for pure logic; focused integration tests for wiring, persistence, permissions, transactions, concurrency, and HTTP contracts; end-to-end tests for critical flows lower-level tests cannot adequately verify.
- Use property-based or mutation testing only when it can expose meaningful gaps; neither is mandatory.
- Prefer Mockito when mocks are needed, without mocking away the behavior under test. Use reflection only when no practical alternative exists.
- Use clear arrange/act/assert; avoid redundant tests, warnings, and empty blocks.
- Keep unit tests deterministic and independent of real network services; use controlled services for integration tests. Use bounded waits and explicit timeouts instead of arbitrary sleeps.
- Never remove or weaken assertions merely to make code pass.

## 6) Logging and observability

- Keep logs actionable and not noisy.
- Use DEBUG for high-volume details; INFO for key lifecycle events; WARN/ERROR for actionable failures.

## 7) Security and data handling

- Never log secrets, tokens, passwords, PII, or payloads containing them.
- Validate all external inputs; use prepared statements / parameterized queries.
- For crypto/auth, do not invent schemes; use existing utilities.

## 8) Performance and reliability

- Prefer streaming over loading big payloads into memory.
- Use explicit timeouts for remote calls. Retry only transient failures when repetition is safe or protected by an idempotency mechanism; bound attempts and total duration, use backoff with jitter, and reuse existing retry handling.
- Avoid blocking calls on event-loop threads.

## 9) Output expectations

- What changed: 1–3 bullets.
- Validation: exact commands executed and their outcomes; identify skipped or blocked checks and explain why.
- Risks or tradeoffs, if any; follow-ups are optional.

## 10) Do not do

- Do not reformat unrelated files.
- Do not rename widely-used public APIs without need.
- Do not add TODOs without a reference (ticket/link) unless requested.
- Do not create SQL functions or triggers without an explicit request from the user.

## 11) Cloud resources and cost

These rules apply to each task that uses AWS or a different paid cloud service.

- Before the first billable action of a task, give the user a cost estimate and get approval for it. The estimate gives:
  - each billable resource, its unit price in the selected region, and the source and date of the price;
  - the expected run time and the total cost, with a margin for one failed or repeated attempt;
  - the cost for each hour and for each month if the resources stay running.
- Get a new approval before the cost or the run time can be more than the approved estimate.
- Keep the scope cost-efficient. Use the smallest instance types, counts and run times that can prove the acceptance condition. Use one region. Do not use paid support tiers, reservations, commitments or paid features that the task does not need.
- Tag each resource with the project tag (`Project=shardshop` for ShardShop), so that a read-only query can find it.
- Do not leave resources in the cloud after the task. Destroy all the resources that the task created before the task ends, also after a failure.
- After the destroy, use read-only queries to make sure that no billable resource stays: tagged resources, instances, NAT gateways, Elastic IPs, load balancers, EBS volumes and snapshots, log groups, ECR repositories and S3 buckets. Report each resource that stays, and its cost.
- If resources must stay while the task waits for the user, tell the user immediately. Give each resource, its cost for each hour and the destroy command.
- Record the start time, the end time and the estimated cost of each billable run in the step record.
- Do not change account settings, IAM permissions, quotas or billing. The user makes these changes.
