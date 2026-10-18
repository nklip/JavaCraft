# Project Instructions for Coding Agents (Codex)

## Subagent model selection

- Use `gpt-5.6-luna` with `low` reasoning effort for straightforward read-only subtasks, such as reading files or URLs, and extracting facts.
- For other subtasks, choose the model and reasoning effort deliberately according to complexity. Use low reasoning for routine edits; reserve higher reasoning for work that warrants it instead of automatically inheriting `ultra` from the main agent.
- Default to `fork_turns="none"`. Give each subagent a compact, self-contained assignment with its objective, relevant file paths, constraints, and acceptance criteria. Include selected history only when needed; use the full conversation only when a concrete dependency cannot be captured in the assignment.
- Delegate when independent work can save time or improve quality. Keep trivial lookups and small, cohesive edits with the main agent. Batch related files, URLs, and inventory questions into one focused assignment rather than creating an agent for each lookup.
- Batch independent searches and file reads. Request only relevant excerpts and concise tool output; reuse established findings instead of repeatedly reading unchanged files, logs, or agent results.
- Ask subagents to return concise findings or changes, supporting file references, validation results, and unresolved questions. Avoid duplicating large source excerpts or full logs in handoffs.
- Confirm requirements and integration boundaries before implementation, and give editing agents clear file ownership to reduce conflicting changes and rework.
- Run all required checks, scoped to the affected modules. Repeat or broaden checks only when changes, failures, or unresolved concerns justify it; token savings must not weaken correctness or required verification.
- Keep synthesis, decisions, and final verification with the main agent. Return ambiguous findings or work requiring deeper reasoning to the main agent.
- The main agent must still read applicable repository instructions and required skills directly.

## 0) Prime directive
- Optimize for: correctness > safety > maintainability > performance.
- Prefer small, reviewable diffs. Avoid broad refactors unless explicitly requested.

## 1) How to work in this repo (non-negotiable)
- Always start by scanning:
    - README.md (setup)
    - ARCHITECTURE.md (boundaries)
    - existing patterns in similar modules
- Keep changes scoped to the requested feature/bugfix.

## 2) Build / test commands (run these)
### Fast local loop
- run maven build command only for the module where you work, don't run build for top project

If any command fails:
- Stop and fix, or explain why it cannot be fixed within scope.

## 3) Coding standards
### General
- Prefer readability over cleverness (KISS).
- Avoid duplication that will likely change together (DRY, but no premature abstractions).
- Keep functions small and cohesive (single responsibility).

### Java specifics (if applicable)
- Prefer constructor injection.
- Avoid static state unless truly constant.
- Use immutable data structures where practical.
- Use explicit timeouts for network calls and retries.

## 4) Architecture rules (boundaries)
- Controllers/handlers: HTTP mapping + validation + translating to domain.
- Services: business logic.
- Repositories/clients: IO only (DB, HTTP, queues).
- Domain must not depend on framework code.
- No cross-module imports that violate layering.

(If rules are unclear, infer from existing code structure and follow it.)

## 5) Testing rules (TDD-friendly defaults)

Java only.

- Cover new or changed observable behavior, relevant boundaries, errors, and regression risks. Reuse or extend existing tests; each test must identify the requirement, plausible failure, or bug it protects against.
- Derive expectations from contracts, requirements, or independent examples; resolve ambiguity instead of encoding assumptions. Assert meaningful results, state, or errors, without copying implementation logic, checking incidental calls, or adding dummy assertions for unused return values.
- Use coverage to find gaps, with no blanket 100% target or tests solely for lines or trivial accessors. Preserve CI gates and required acceptance checks.
- Bugfixes need a regression test verified to fail for the reported reason before the fix and pass afterward. If automation is impractical, document alternative verification and its limits. TDD is optional when it helps clarify behavior.
- Prefer unit tests for pure logic; focused integration tests for wiring, persistence, permissions, transactions, concurrency, and HTTP contracts; end-to-end tests for critical flows lower-level tests cannot adequately verify.
- Use property-based or mutation testing only when it can expose meaningful gaps; neither is mandatory.
- Prefer Mockito when mocks are needed, without mocking away the behavior under test. Use reflection only when no practical alternative exists.
- Use clear arrange/act/assert; avoid redundant tests, warnings, and empty blocks.
- Keep unit tests deterministic and independent of real network services; use controlled services for integration tests. Use bounded waits and explicit timeouts instead of arbitrary sleeps.
- Run relevant existing tests and required checks for affected modules; repeat or broaden only for changes, failures, or unresolved concerns. Never remove or weaken assertions merely to make code pass.

## 6) Logging / observability
- Logs must be:
    - actionable
    - not noisy
    - not leaking secrets/PII
- Use DEBUG for high-volume details; INFO for key lifecycle events; WARN/ERROR for actionable failures.

## 7) Security and data handling
- Never log secrets, tokens, passwords, full payloads with PII.
- Validate all external inputs.
- Use prepared statements / parameterized queries.
- For crypto/auth, do not invent schemes—use existing utilities.

## 8) Performance / reliability defaults
- Prefer streaming over loading big payloads into memory.
- Add timeouts + retries with backoff for remote calls (if there is already a standard utility, use it).
- Avoid blocking calls on event-loop threads (if applicable).

## 9) Output expectations (what “done” means)
When you finish a task, provide:
- What changed (1–3 bullets)
- How to test (exact commands)
- Risks / tradeoffs (if any)
- Follow-ups (optional)

## 10) “Do not do”
- Do not reformat unrelated files.
- Do not rename widely-used public APIs without need.
- Do not add TODOs without a reference (ticket/link) unless requested.
- Do not create SQL functions or triggers without an explicit request from the user.
