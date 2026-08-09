# Final Engineering Summary

## Plan and rationale

The assignment asks for two things bundled together: a working URL shortener, and an agentic
orchestration layer governing its SDLC — explicitly called out as the "critical
differentiator" being evaluated. The plan treated these as genuinely separate concerns from
the start: `service` is a normal, independently deployable Spring Boot application that would
make sense on its own; `orchestrator` is a separate tool that drives and gates changes to it,
with no runtime coupling in either direction. Both live in one Maven reactor build so there's
a single toolchain and a single `mvn test` to verify everything.

Sequencing: domain model → repository layer → business logic → REST layer → tests → Docker/API
spec for `service`; then engine core (graph/state machine/audit) → approvals/policy →
stage executors/metrics/reporting/CLI → tests for `orchestrator`; then a real brownfield
enhancement (scheduled expired-link cleanup) to have genuine before/after history for the
brownfield scenario to reason about; then all three required scenarios were actually executed
end-to-end (not just described) and their real output committed as evidence; then this
documentation set, written last so it could describe what was actually built and actually
observed rather than what was merely planned.

Two decisions were made jointly with the person directing this work rather than assumed
unilaterally: keeping the entire project in Java (no polyglot orchestrator) once it was
clear the service was already Java/Spring Boot, and choosing SQLite + `JdbcTemplate` over a
heavier persistence stack for a prototype of this scope.

## Artifacts produced

- `service/` — Spring Boot URL shortener: 6 REST endpoints, SQLite persistence, idempotency,
  rate limiting, scheme validation, async click tracking, analytics, a scheduled expired-link
  cleanup job, 35 tests, a Dockerfile, and a hand-authored OpenAPI spec.
- `orchestrator/` — a plain-Java SDLC orchestration engine: config-driven dependency graph,
  real parallel scheduling, human approval gates (interactive + replay), 3 policy guardrails
  (each labeled against the security/compliance/change-control categories the assignment
  names) including a secret scanner, bounded retry/fallback/rollback/safe-stop, an append-only
  JSONL audit log, reliability-metrics computation, Markdown report generation, a CLI, and 34 tests.
- `orchestrator/scenarios/` and `orchestrator/runs/` — inputs, approval fixtures, and real
  executed output (audit logs, reports, metrics) for all three required scenarios.
- `docs/` — this document, plus `architecture.md`, `scenarios.md`, `setup.md`, and
  `testing-and-tradeoffs.md`.
- Root `README.md` — architecture overview, endpoint table, key decisions, setup, and example
  requests.

## Risks, trade-offs, and validation

The single highest-risk correctness property in `service` is short-code collision handling
under concurrency; it was validated with a test specifically designed to force a real race
(`ShortCodeCollisionConcurrencyTest`, 50 concurrent threads against a deliberately shrunk
2-slot code space) and to assert against the actual database state afterward, not against
mocked behavior. The single highest-risk *unverified assumption* going in — how SQLite's
unique-constraint violations actually surface through Spring's JDBC exception translation —
was resolved by writing a throwaway experiment test to observe the real exception type before
writing the retry logic against it, rather than assuming standard Spring behavior would apply.

The orchestrator's core risk is that a workflow engine can trivially "look" correct while
doing nothing real — every stage executor was deliberately built to shell out to real tools
(`mvn compile`, `mvn test` + Surefire XML parsing, `git diff`/`git status`) or perform real
checks (file/YAML validation, regex ambiguity/secret scanning) rather than returning canned
success. This was validated by actually running all three required scenarios against the real
`service` module and inspecting the resulting audit logs for genuine signal (real compile
output, real test counts, real parallel-execution timestamps, a real 10-term ambiguity flag
and a real runtime graph mutation in the ambiguous scenario) rather than trusting that the
code "should" work. See `docs/testing-and-tradeoffs.md` for the full list of trade-offs
accepted to keep this scope achievable, and the explicit safety boundary around what rollback
and release deliberately do not automate.

## Assumptions

- A single-instance, prototype-grade deployment is acceptable (explicitly documented rather
  than silently assumed) — SQLite and the in-memory rate limiter both encode this assumption.
- "Real" orchestration for this assignment means genuinely executing and gating on real
  signals from the codebase (compilation, tests, diffs, docs), not necessarily generating code
  live — this interpretation is stated explicitly in `docs/scenarios.md` rather than left
  ambiguous, since overclaiming live code generation would misrepresent what the system does.
- Three scenarios, each demonstrating decomposition/orchestration/validation, satisfies the
  assignment's scenario requirement without needing a fourth failure-injection scenario to
  additionally demonstrate retry/fallback/rollback in a full run (that machinery is instead covered by
  the orchestrator's own unit tests and `service`'s intentionally-flaky test fixture).

## Limitations

See `docs/testing-and-tradeoffs.md` for the complete list. In summary: SQLite single-writer/
single-instance, non-distributed in-memory rate limiting, no UA-parsing library, no runtime
OpenAPI generation, a fixed 3-rule (not generalized) policy engine, and an orchestrator whose
`implement-*`/`testing`/`documentation` stages verify and gate already-written code rather than
author it live.
