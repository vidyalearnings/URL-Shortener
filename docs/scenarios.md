# Scenarios

Three required scenarios, each actually executed against the real orchestrator (not
described hypothetically) — evidence for each lives in `orchestrator/runs/<scenario>/`:
`audit.jsonl` (raw event log), `report.md` (rendered timeline), `metrics.txt` (reliability
metrics computed from the audit log). Run inputs live in `orchestrator/scenarios/`.

## 1. Greenfield — build the service from scratch

**Input:** [`orchestrator/scenarios/inputs/requirements-greenfield.md`](../orchestrator/scenarios/inputs/requirements-greenfield.md)
— a precisely-specified requirement (explicit status codes, explicit retry bound, explicit
rate-limit numbers) with no vague/unquantified language.

**Graph:** the full base graph (`graphs/graph.yaml`): `requirements → design → (implement-api ‖
implement-analytics ‖ implement-reliability) → (testing ‖ documentation) → release-readiness`.

**Decomposition:** the requirement decomposes along exactly the module boundaries the
`implement-*` stages already assume — API surface (create/redirect/get/update/delete),
analytics (click aggregation/breakdown), and reliability (collision handling, idempotency,
rate limiting, scheme validation). `design`'s exit approval gate is where that decomposition
is reviewed and signed off before implementation stages are allowed to run.

**Orchestration in action:** all three `implement-*` stages start within milliseconds of each
other (see `report.md` — a real `ExecutorService` fan-out, not a simulated one) and are
independently synchronized before `testing`/`documentation` begin. Each `implement-*` stage
ran `mvn -pl service compile` for real and reported `exit=0`; `testing` ran the *real* `mvn -pl
service test` (all 35 service tests) and `documentation` validated the real root `README.md`
and `service/openapi/openapi.yaml`.

**Validation:** `release-readiness` aggregated real signals — testing succeeded, documentation
checks passed, no policy violations — and only finalized after the `release-approval` human
gate (`orchestrator/scenarios/approvals/greenfield-approvals.yaml`) was granted.

**Honesty note:** the `service` module's source code was authored ahead of this orchestrator
run, as ordinary incremental git commits (see the repo's commit history) — it was not
generated live by the `implement-*` stages. Those stages *verify and gate* the already-written
code (compile it for real, diff it for real, test it for real); they do not write it. This is
stated plainly here rather than implied otherwise: the orchestrator's job in this scenario is
governance and verification of engineering output, not code generation.

**Result:** 8/8 nodes `SUCCEEDED`, 0 retries, 0 rollbacks, 100% success rate, ~14.3s end-to-end
(dominated by the real `mvn test` run). See `orchestrator/runs/greenfield/metrics.txt`.

## 2. Brownfield — expired-link cleanup job

**Change:** `service` previously only checked link expiry lazily, at read time
(`ShortUrl.isExpired()`). The enhancement adds `ExpiredLinkCleanupJob`, a `@Scheduled` sweep
that proactively transitions overdue `ACTIVE` rows to a new `EXPIRED` status (see
`docs/architecture.md` and the commit `feat(service): add scheduled sweep to actively expire
overdue links`). This was chosen deliberately over "fixing" the collision-handling logic,
which is already correct by design from the first commit — staging a fake bug to "fix" would
have been a dishonest demonstration of brownfield reasoning.

**Graph:** the trimmed graph (`graphs/graph-brownfield.yaml`): `requirements` and `design` are
*not present at all* (not skipped-with-a-flag — genuinely absent from the loaded graph),
starting directly at `implement-reliability → (testing ‖ documentation) → release-readiness`.
This demonstrates the engine supports graphs that don't include every possible stage, which
matters for real brownfield work where re-deriving requirements/design from scratch for a
small, well-scoped change would be wasted process.

**Codebase reasoning:** `implement-reliability`'s executor ran `git diff --name-only HEAD~1`
scoped to reliability-related file patterns to identify impacted files, and `mvn -pl service
compile` to confirm the change actually compiles — this is the literal "identify
impacted modules" requirement, backed by a real `git diff` against the real repo, not a
hardcoded list.

**Result:** 4/4 nodes `SUCCEEDED`, 100% success rate, ~13.2s end-to-end. See
`orchestrator/runs/brownfield/metrics.txt` and `report.md`.

## 3. Ambiguous — "make the analytics better"

**Input:** [`orchestrator/scenarios/inputs/requirements-ambiguous.md`](../orchestrator/scenarios/inputs/requirements-ambiguous.md)
— deliberately vague ("should be fast", "needs to be scalable", "should be secure", "TBD",
"simple and intuitive").

**What actually happened (not simulated):** the `requirements` stage's regex ambiguity
scanner flagged **10 separate matches** across 4 lines (`fast`, `should` ×2, `scalable`,
`tbd`, `secure`, `simple`, `intuitive`, `robust`, `efficient` — see the `DECISION` audit event
in `orchestrator/runs/ambiguous/audit.jsonl`). Because ambiguity was found, the stage called
`Replanner.insertNode(...)` for real, inserting a synthetic `clarify-requirements` node into
the *live* graph, rewiring `design` to depend on it, and cascading `design` to `STALE`
(`NODE_INSERTED` and `NODE_STALE`/`REPLAN` events in the same audit log). The
`clarify-requirements` node was gated by its own approval
(`clarify-requirements-approval` in `orchestrator/scenarios/approvals/ambiguous-approvals.yaml`),
which supplied the concrete clarification actually used to unblock the graph: a p95 < 200ms
redirect-latency target at up to 100k clicks/day, a defined rate-limit policy reusing the
existing per-IP token bucket, and an explicit scope cut (no dashboard UI — only the analytics
data the API already exposes). The graph then proceeded normally through `design`,
implementation, testing, documentation, and release-readiness.

**Validation:** this demonstrates requirement understanding *and* ambiguity handling together
— the system didn't guess at what "fast" or "secure" meant, it flagged the gap, required a
human decision to resolve it, recorded that decision as an auditable artifact, and only then
let downstream engineering work proceed against the now-concrete spec.

**Result:** 9/9 nodes `SUCCEEDED` (the extra node is `clarify-requirements`), 100% success
rate, ~14.2s end-to-end. See `orchestrator/runs/ambiguous/metrics.txt` and `report.md`.

## Cross-scenario observations

- Retry/rollback/fallback frequency is 0 across all three real runs, because nothing actually
  failed — reliability metrics with real non-zero signal instead come from `service`'s own test
  suite (`FlakyClickTimingTest`, a deliberately calibrated ~25%-failure fixture) exercised via
  `mvn test`, and from the orchestrator's own `RetryRollbackTest`/`FallbackTest`/
  `StateMachineTest` unit tests, which exercise the retry/backoff/fallback/rollback/safe-stop
  machinery directly with synthetic failing stages. See `docs/testing-and-tradeoffs.md`.
- All three runs are individually reproducible via the `REPLAY` approval mode (no interactive
  input required) — see `docs/setup.md` for the exact commands.
