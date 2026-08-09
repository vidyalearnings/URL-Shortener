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

**`orchestrator` (32 tests).** `GraphTest` (loading + cycle detection against both a valid and
a deliberately-cyclic fixture), `StateMachineTest`/executor tests that run real synthetic
graphs through the real `Orchestrator` and assert wall-clock overlap between parallel branches
(not just that both eventually ran), `RetryRollbackTest` (fake stages that fail N times then
succeed, and fake stages that always fail, asserting retry counts, backoff, `FAILED`
transitions, rollback, and safe-stop), `PolicyRuleTest` and `SecretScannerTest` (all 3 rules in
isolation, including no-false-positive checks), `RequirementsStageTest` (ambiguity detection
against both vague and well-specified fixture text), `MetricsCalculatorTest` (hand-calculated
expected values against a synthetic audit log fixture), `ApprovalGateTest` (replay mode
grant/deny/lookup).

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
- **The 3 real scenario runs all show 0 retries/0 rollbacks**, because nothing in them actually
  failed — real, non-simulated retry/rollback/MTTR signal instead comes from `service`'s
  intentionally-flaky test fixture (exercised via `mvn test`, not via a scenario run) and from
  the orchestrator's own unit tests that drive the retry/rollback machinery directly against
  synthetic failing stages. A fourth "failure-injection" scenario demonstrating a real failed
  orchestration run end-to-end would strengthen this further but wasn't included, to keep the
  three required scenarios focused on what the assignment specifically asks for
  (greenfield/brownfield/ambiguous, not a fourth failure-mode scenario).
- **`docFiles`/`openapiPath` checks in `DocumentationStage` are configured non-strict
  (`strict: false`) in the shipped graphs.** A missing or empty doc file is reported as an
  issue but does not fail the stage. This was chosen so the `documentation` stage stays usable
  standalone/in isolation (e.g. in orchestrator unit tests, or if `service` is mid-refactor and
  temporarily missing a doc file) rather than becoming a hard gate on unrelated work; the
  trade-off is that a real documentation regression wouldn't block a release on its own —
  policy rule 1 (testing must succeed) is the actual hard release gate in this prototype.

## Risks not fully mitigated

- **Windows timer granularity** made calibrating `FlakyClickTimingTest`'s failure window
  non-trivial (`Thread.sleep`'s ~15ms granularity made naive sleep-based timing windows always
  pass); the fix (a `System.nanoTime()` busy-wait) is correct but is the kind of platform detail
  worth flagging for anyone running this suite on a different OS/scheduler.
- **`baseRef: HEAD~1`** in the shipped graph YAML is a fixed relative git ref for the
  `implement-*` stages' impacted-file diff. It works for the scenarios as committed here, but a
  more general orchestrator would need this to be a run-time parameter (e.g. "diff against the
  branch point") rather than a fixed offset, to stay correct as more commits accumulate.
