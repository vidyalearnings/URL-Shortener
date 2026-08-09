# Architecture

## Components

```
URL-Shortener/
├── service/        Spring Boot 3.5 URL shortener (Java 21) - REST API + SQLite
└── orchestrator/    Plain-Java SDLC orchestration engine (Java 21) - no Spring, no runtime
                      dependency on `service`; drives and gates changes to it
```

The two modules are independent artifacts. `service` is a normal, deployable Spring Boot
application with no awareness that an orchestrator exists. `orchestrator` is a separate tool
(packaged as a runnable shaded jar) that treats `service` as a directory it can inspect and
run Maven/git commands against — the relationship is one-directional and external, the same
way a CI pipeline relates to the repo it builds.

### `service` — layered, JdbcTemplate-based

```
controller/   REST endpoints (UrlController, RedirectController)
service/      Business logic (short-code generation, validation, rate limiting, analytics,
              the async click tracker, the expired-link cleanup job)
domain/       ShortUrl, ClickEvent, UrlStatus - plain records/enum, no framework annotations
repository/   JdbcTemplate-based data access (UrlRepository, ClickEventRepository,
              IdempotencyKeyRepository) + SqliteConstraintViolations helper
dto/          Request/response records
exception/    Custom exceptions + a single @RestControllerAdvice GlobalExceptionHandler
config/       AppProperties, AsyncConfig, RateLimitInterceptor, WebMvcConfig, SchedulingConfig
```

Persistence is SQLite via a hand-written `schema.sql` plus plain `JdbcTemplate` calls — no
JPA/Hibernate. There is exactly one `HikariCP` connection (`maximum-pool-size: 1`), because
SQLite only supports a single writer; `journal_mode=WAL` and a `busy_timeout` pragma keep
concurrent requests serializing cleanly on that one connection instead of throwing spurious
"database is locked" errors.

### `orchestrator` — a small, inspectable workflow engine

```
engine/       Graph (YAML-loaded DAG + cycle detection), Orchestrator (scheduler/executor),
              RunContext (cross-stage state + decision lineage), Replanner (runtime graph
              mutation), StageState / StageDef / RetryPolicyDef
stages/       One StageExecutor implementation per SDLC stage - each does real work (see below)
audit/        AuditEvent + AuditLogger - append-only JSONL, single source of truth
policy/       PolicyEngine + exactly 3 rules (stage-succeeded gate, human-approval gate,
              secret-scan gate) + SecretScanner
approvals/    ApprovalGate - interactive (stdin) or replay (YAML fixture) modes
metrics/      MetricsCalculator - reads audit.jsonl, computes reliability metrics
reporting/    ReportGenerator - renders a Markdown run report from audit.jsonl
cli/          Main - run-scenario / metrics / report commands
```

The stage graph is **not hardcoded** — it's loaded from YAML
(`src/main/resources/graphs/graph.yaml` and `graph-brownfield.yaml`), so adding, removing, or
rewiring stages is a config change, not a code change.

## Orchestration model

```
requirements ──▶ design ──▶ ┬─ implement-api ─────────┐
                             ├─ implement-analytics ────┼──▶ ┬─ testing ───────┐
                             └─ implement-reliability ──┘    └─ documentation ──┴──▶ release-readiness
```

- **Sequential + parallel with synchronization**: `design` depends on `requirements`
  (sequential); the three `implement-*` stages run *concurrently* once `design` succeeds (a
  real `ExecutorService`-backed fan-out, not simulated — see the near-identical start
  timestamps for all three in `orchestrator/runs/greenfield/report.md`); `testing` and
  `documentation` both wait on *all three* `implement-*` stages (fan-in) and then run
  concurrently themselves; `release-readiness` waits on both.
- **Entry/exit gates**: `design` has an *exit* approval gate (architecture review, before it's
  considered done); `release-readiness` has an *entry* approval gate (a human must approve
  release before that stage even starts).
- **Cross-stage context & decision lineage**: every stage writes into a shared `RunContext`
  (`outputs` per node, plus an explicit `decisions` list); the `requirements` stage's ambiguity
  findings, `design`'s rationale, and `release-readiness`'s final aggregation are all readable
  by later code and are independently reconstructable from the audit log alone.
- **Bounded retries**: each node has a `RetryPolicyDef` (max attempts, exponential backoff)
  evaluated by the `Orchestrator`, with real `Thread.sleep`-based backoff between attempts,
  logged as a `RETRY` event per attempt.
- **Fallback** — distinct from retry (same executor, tried again) and from rollback (undoing
  state after failure): if a node has a `fallback` executor configured in its graph YAML and
  the primary executor exhausts its retry budget, the fallback is attempted once (no retries of
  its own). If the fallback succeeds, the node ends `SUCCEEDED` via the degraded path (recorded
  in its outputs as `viaFallback: true` plus the primary failure reason, and as a `FALLBACK`
  audit event) instead of failing outright. Only if the fallback also fails does the node fall
  through to the failure path below. Concretely wired in: `documentation`'s fallback is
  `MinimalDocumentationStage`, which drops the strict OpenAPI/doc-completeness check down to
  "does README.md exist and have content" — see `stages/MinimalDocumentationStage.java`.
  Proven by `engine/FallbackTest.java` (primary-fails-then-fallback-succeeds, and
  primary-and-fallback-both-fail) since — like retry and rollback — the shipped scenarios are
  designed to succeed cleanly and don't naturally exercise it (see `docs/scenarios.md`).
- **Rollback + safe-stop**: on retry exhaustion (and no fallback configured, or the fallback
  also failed) the node transitions `FAILED`, its `rollback()` path fires (orchestration-state
  rollback only — see Safety boundary below), downstream dependents cascade to `ROLLED_BACK`,
  and the scheduler stops admitting *any* new work (global safe-stop) while letting
  already-running siblings finish.
- **Policy guardrails**: `PolicyEngine` evaluates exactly 3 rules (see `policy/policy.yaml`),
  each mapped to one of the assignment's three named guardrail categories via
  `PolicyRule.category()`: `RequireStageSucceededRule` (**compliance** — testing must have
  succeeded before `release-readiness` proceeds, independent of any approval),
  `RequireHumanApprovalRule` (**change control** — a release requires a recorded human
  `APPROVAL_GRANTED` decision, independent of what automated checks say), and
  `NoSecretsInChangedFilesRule` (**security** — each `implement-*` stage is preceded by a regex
  secret scan via `SecretScanner` over `git diff`/`git status`-changed files in `service/`).
- **Audit-grade observability**: `AuditLogger` appends one JSON object per event
  (`STAGE_TRANSITION`, `RETRY`, `FALLBACK`, `ROLLBACK`, `POLICY_VIOLATION`,
  `APPROVAL_REQUESTED`, `APPROVAL_GRANTED`/`DENIED`, `DECISION`, `REPLAN`, `NODE_INSERTED`,
  `NODE_STALE`) to `audit.jsonl`, flushed immediately — this file is the *only* source
  `MetricsCalculator` and `ReportGenerator` read from, so what you see in a report is provably
  what happened, not a separately-maintained summary that could drift from reality.
- **Reliability metrics**: computed from the audit log — success rate, retry/rollback/fallback
  frequency, MTTR (mean gap between a node's `FAILED` and its subsequent `SUCCEEDED`), and
  end-to-end latency. See `orchestrator/runs/*/metrics.txt` for real output from each scenario.
- **Dynamic re-planning**: the `requirements` stage scans for vague/unquantified language; if
  it finds any, it calls `Replanner.insertNode(...)` to add a synthetic `clarify-requirements`
  node into the *live* graph (gated by its own approval), rewires `design` to depend on it, and
  marks `design` `STALE`. This is exercised for real in the ambiguous scenario — see
  `docs/scenarios.md` and `orchestrator/runs/ambiguous/audit.jsonl`.

### Safety boundary: what "rollback" and "release" do and don't do

Two things are deliberately *not* automated, and this is a design decision, not an oversight:

1. **Rollback is orchestration-state only.** It reverts `RunContext.outputs` for the failed
   node and cascades downstream state — it never runs `git revert`/`git reset`/`git clean`
   against the actual `service` repo. Automating destructive git operations from a workflow
   engine is a real production hazard; a prototype is not the place to build that.
2. **`release-readiness`'s final "released" event is log-only.** It aggregates real signals
   (tests passed, docs checked, no unresolved policy violations) and requires a human approval
   gate, then writes a synthetic version tag to the audit log — it does not run `git tag`,
   `git push`, or any deployment command. Wiring a prototype's release stage to real deploy
   infrastructure would be unsafe without a great deal more guardrail work than fits this scope.

## Key decisions

| Decision | Rationale |
|---|---|
| JdbcTemplate over JPA | SQLite's Hibernate dialect support is inconsistent across Spring Boot versions; hand-written SQL against a hand-written schema sidesteps that entirely. |
| Orchestrator is plain Java, not Python/Airflow/Temporal | `service` is already Java/Spring Boot; a second language would mean a second toolchain for no functional benefit — nothing about a DAG/state-machine engine requires Python, and a heavy workflow framework would obscure the control flow a reviewer is meant to be able to read. |
| DB unique constraint as the collision backstop, not just an app-level check | Only the database can arbitrate a genuine concurrent race correctly; the app-level retry loop is what makes that arbitration transparent to the caller (see `ShortCodeCollisionConcurrencyTest`). |
| Stage graph in YAML, not hardcoded | Matches the assignment's explicit requirement for "an explicit dependency graph" and lets the brownfield scenario reuse the same engine with a genuinely different graph (`graph-brownfield.yaml`) with zero code changes. |
| `implement-*`/`testing`/`documentation` stages do real work (`mvn compile`/`test`, `git diff`, file/YAML checks) rather than being mocked | A workflow engine that always reports success regardless of the underlying system's actual state isn't demonstrating orchestration, it's demonstrating theater. Real signals mean real retries, real failures, and real metrics. |
| Rollback and release are deliberately non-destructive / log-only | Matches "controlled autonomy": agents execute multi-step work, but destructive or externally-visible actions (deleting git history, deploying) stay outside what this prototype automates. |
