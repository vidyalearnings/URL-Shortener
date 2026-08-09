# Testing Approach, Limitations, and Trade-offs

## Testing approach

**`service` (35 tests).** Layered: unit tests for pure logic (`ShortCodeGeneratorTest`),
`@SpringBootTest`-level tests for controllers/integration (`UrlControllerTest`,
`RedirectControllerTest`, `AnalyticsTest`, `UrlShortenerIntegrationTest`,
`ExpiredLinkCleanupJobTest`), and one test specifically designed to prove a reliability
property under real concurrency rather than assert it by inspection
(`ShortCodeCollisionConcurrencyTest` — 50 threads racing for 2 remaining short-code slots via
`ExecutorService` + `CountDownLatch`, asserting `COUNT(*) == COUNT(DISTINCT short_code)` in
the actual database afterward). Each `@SpringBootTest` class uses an isolated temp SQLite file
via `@DynamicPropertySource` so tests never interfere with each other's data. A dedicated
`UrlRepositoryExceptionTranslationExperimentTest` exists specifically to *empirically observe*
which exception type SQLite unique-constraint violations actually surface as through Spring's
JDBC layer, rather than assuming — the answer (`UncategorizedSQLException`, not
`DuplicateKeyException`) directly shaped `SqliteConstraintViolations`.

**`orchestrator` (34 tests).** `GraphTest` (loading + cycle detection against both a valid and
a deliberately-cyclic fixture), `StateMachineTest`/executor tests that run real synthetic
graphs through the real `Orchestrator` and assert wall-clock overlap between parallel branches
(not just that both eventually ran), `RetryRollbackTest` (fake stages that fail N times then
succeed, and fake stages that always fail, asserting retry counts, backoff, `FAILED`
transitions, rollback, and safe-stop), `FallbackTest` (a primary stage that always fails paired
with a fallback that succeeds — asserting the node ends `SUCCEEDED` via the degraded path with
exactly one fallback attempt and a `FALLBACK` audit event; and the case where the fallback also
fails — asserting the normal `FAILED`/rollback path still applies), `PolicyRuleTest` and
`SecretScannerTest` (all 3 rules in isolation, including no-false-positive checks and each
rule's `category()`), `RequirementsStageTest` (ambiguity detection against both vague and
well-specified fixture text), `MetricsCalculatorTest` (hand-calculated expected values against
a synthetic audit log fixture), `ApprovalGateTest` (replay mode grant/deny/lookup).

**End-to-end.** All three required scenarios (`docs/scenarios.md`) were actually executed
against the real, built `service` module — not run once during development and then described
from memory. Their `audit.jsonl`/`report.md`/`metrics.txt` are committed as evidence.

## Deliberate scope cuts

These were cut consciously, not missed:

- **No UA-parsing library.** `AnalyticsService`'s referrer/user-agent breakdown is raw
  top-N string grouping. A real UA parser (device/OS/browser breakdown) would be a bounded
  addition on top of the existing `click_events` table, not a redesign.
- **No springdoc/runtime OpenAPI generation.** `service/openapi/openapi.yaml` is hand-authored
  and hand-maintained. Fine for this scope; would drift from the code over time on a real team
  without either springdoc or a contract test pinning them together.
- **No generic policy rule DSL.** The `PolicyEngine` has exactly the 3 rules the assignment
  calls for, hardcoded as classes, not a configurable rule language. A real platform-wide
  guardrail system would need the latter; a 3-rule prototype does not.
- **Uniform retry policy shape, not per-node bespoke backoff curves.** Every node uses the same
  `RetryPolicyDef` shape (max attempts + exponential backoff), just with different numbers per
  node in the YAML. Sufficient to demonstrate bounded retry with real backoff; a production
  system might want jitter, circuit-breaking, or different retry semantics per failure class.

## Known limitations

- **SQLite is single-writer, single-instance.** `hikari.maximum-pool-size=1` is correct for
  this reason but means `service` cannot be horizontally scaled against a shared DB file as-is;
  a real multi-instance deployment would need Postgres/MySQL (a `JdbcTemplate`-based repository
  layer with hand-written SQL — no ORM dialect — makes that swap comparatively low-risk, but it
  is not done here).
- **Rate limiting is in-memory, per-instance.** `RateLimiterService`'s token buckets live in a
  `ConcurrentHashMap` inside one JVM. Multiple instances behind a load balancer would each
  enforce their own independent limit rather than a shared one — would need Redis or similar
  for a real distributed deployment.
- **The orchestrator's `implement-*`/`testing`/`documentation` stages verify and gate
  already-written code; they do not generate it.** This is stated plainly in
  `docs/scenarios.md` rather than left implicit. What they verify is real (real `mvn compile`,
  real `mvn test` + Surefire parsing, real `git diff`, real file/YAML checks) — but the
  orchestrator's role in this prototype is governance of engineering output, not authorship of
  it, and the two should not be conflated when reading the scenario evidence.
- **Rollback is orchestration-state-only; release is log-only.** See
  `docs/architecture.md`'s "Safety boundary" section — this is a deliberate scope boundary, not
  an unfinished feature. Automating destructive git operations or real deployments from a
  prototype workflow engine would be a genuine safety risk, not a capability gap worth closing
  here.
- **The 3 real scenario runs all show 0 retries/0 rollbacks/0 fallbacks**, because nothing in
  them actually failed — real, non-simulated retry/rollback/fallback/MTTR signal instead comes
  from `service`'s intentionally-flaky test fixture (exercised via `mvn test`, not via a
  scenario run) and from the orchestrator's own unit tests (`RetryRollbackTest`, `FallbackTest`)
  that drive the retry/rollback/fallback machinery directly against synthetic failing stages. A
  fourth "failure-injection" scenario demonstrating a real failed orchestration run end-to-end
  would strengthen this further but wasn't included, to keep the three required scenarios
  focused on what the assignment specifically asks for (greenfield/brownfield/ambiguous, not a
  fourth failure-mode scenario).
- **`docFiles`/`openapiPath` checks in `DocumentationStage` are strict (`strict: true`) in the
  shipped graphs** — a missing/empty doc file or an invalid OpenAPI schema fails the stage after
  its retry budget, at which point the `documentation` node's configured fallback
  (`MinimalDocumentationStage`, wired via `fallback: documentation-minimal`) degrades to the
  minimum bar (README.md exists and is non-empty) rather than failing the whole
  release-readiness path outright. `DocumentationStage` retains a `strict` param for standalone/
  unit-test use (non-blocking mode) but the shipped graphs use the stricter, gated behavior.

## Failure scenarios and system response

| Scenario | Trigger | Response |
|---|---|---|
| Short-code collision race | Concurrent creates land on the same generated code | DB unique constraint rejects the loser; app-level retry generates a fresh code, up to `max-generation-attempts` (default 5), then `503`. Proven under real concurrency by `ShortCodeCollisionConcurrencyTest`. |
| Custom alias already taken | Two creates target the same `customAlias` | No retry (a real conflict, not a transient collision) — `409` via `AliasAlreadyExistsException`. |
| Short-code space exhausted | Generation retries all collide | `503` via `ShortCodeGenerationException` rather than hanging or returning a malformed response. |
| Async click-write fails | `ClickTrackingService`'s `@Async` method throws (e.g. transient DB contention) | Swallowed by Spring's default async-uncaught-exception handling (logged, not propagated) — **by design**, since analytics recording must never fail or delay the redirect response itself. |
| Transient SQLite lock beyond `busy_timeout` on a non-collision write (update/delete) | Sustained write contention | **Known gap, not silently glossed over**: falls through to Spring Boot's default error response, not the app's custom `ErrorResponse` JSON shape, since `GlobalExceptionHandler` has no catch-all `Exception` handler — only the specific exceptions it's told to expect. A production hardening pass would add one. |
| Rate limiter unbounded growth | Sustained traffic from many distinct client IPs over a long-running instance | **Known gap**: `RateLimiterService`'s `ConcurrentHashMap<String, TokenBucket>` never evicts idle entries — acceptable for a prototype/demo run, not for long-lived production uptime without an eviction policy (e.g. a scheduled sweep, mirroring `ExpiredLinkCleanupJob`'s pattern). |
| Orchestrator stage executor throws | Any unexpected exception inside a `StageExecutor.execute` | Caught by the `Orchestrator`'s retry loop exactly like a returned failure — retried per `RetryPolicyDef`, then fallback (if configured) — then `FAILED`/rollback/safe-stop. |
| Human denies an approval gate | `ApprovalGate` returns `approved=false` | Node fails immediately with no retry (a human "no" isn't a transient error to retry past) — cascades to rollback/safe-stop like any other failure. |
| Policy violation (e.g. secret-shaped string detected) | `NoSecretsInChangedFilesRule` blocks an `implement-*` stage | Stage never executes (blocked pre-execution) — `POLICY_VIOLATION` audit event, node fails with no rollback needed (nothing ran), safe-stop halts the run. |
| `mvn`/`git` not resolvable on `PATH` | Minimal/non-standard shell environment running the orchestrator | `AbstractImplementStage`/`TestingStage` fall back to a hardcoded Maven install path before giving up; if that also fails, the stage fails normally through the standard retry/fallback/rollback path — not a special case. |

## Risks not fully mitigated

- **Windows timer granularity** made calibrating `FlakyClickTimingTest`'s failure window
  non-trivial (`Thread.sleep`'s ~15ms granularity made naive sleep-based timing windows always
  pass); the fix (a `System.nanoTime()` busy-wait) is correct but is the kind of platform detail
  worth flagging for anyone running this suite on a different OS/scheduler.
- **`baseRef: HEAD~1`** in the shipped graph YAML is a fixed relative git ref for the
  `implement-*` stages' impacted-file diff. It works for the scenarios as committed here, but a
  more general orchestrator would need this to be a run-time parameter (e.g. "diff against the
  branch point") rather than a fixed offset, to stay correct as more commits accumulate.
