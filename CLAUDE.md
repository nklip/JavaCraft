# Project Instructions for Coding Agents (Claude Code)

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
- New behavior must have tests.
- All new lines should be covered 100% with test coverage.
- Bugfixes require a failing test first, then the fix.
- Prefer:
    - unit tests for pure logic
    - integration tests only where wiring/IO matters
- Tests must assert behavior, not implementation details.
- Prefer to use mockito instead of creating a stub class
- Use reflection ONLY and ONLY if there is not other option

### Test quality checklist
- Clear arrange/act/assert.
- Stable (no sleeps, no real network).
- Covers edge cases and error paths.
- No warnings
- No empty blocks
- Any method output should not be ignored, use any assertion to cover it

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