# URL Shortener + Agentic SDLC Orchestrator

A URL shortener service, built and governed by a custom SDLC orchestration engine that
demonstrates dependency-graph-based workflow orchestration with approval gates, policy
guardrails, retries/rollback, audit logging, and reliability metrics.

Two Maven modules in one build:

- **`service/`** — the URL shortener itself (Spring Boot 3.5, Java 21, SQLite).
- **`orchestrator/`** — a plain-Java SDLC orchestration engine that runs the service
  through requirements → design → implementation → testing → documentation →
  release-readiness, with real gates, retries, policy checks, and audit logging.

See [`docs/architecture.md`](docs/architecture.md) for the full design, and
[`docs/scenarios.md`](docs/scenarios.md) for the three required scenario walkthroughs
(greenfield / brownfield / ambiguous) with links to their actual audit logs and reports.

## Architecture

```
┌────────────────────────┐        governs         ┌──────────────────────────┐
│   orchestrator module   │ ──────────────────────▶ │      service module      │
│  (Java, no Spring)      │  mvn compile/test,       │  (Spring Boot 3.5)       │
│  graph/state machine,   │  git diff, doc checks    │  REST API + SQLite       │
│  audit log, policy,     │                          └──────────────────────────┘
│  approvals, metrics     │
└────────────────────────┘
```

The service is a normal, independently runnable Spring Boot app. The orchestrator is a
separate tool that drives and gates changes to it — it is not a runtime dependency of the
service, and the service has no dependency on the orchestrator.

## Endpoints (service)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/urls` | Create a shortened URL. Optional `customAlias`, `expiresAt`, `Idempotency-Key` header. |
| `GET` | `/{code}` | 302 redirect to the original URL; records a click asynchronously. |
| `GET` | `/api/urls/{code}` | Get metadata for a short code. |
| `GET` | `/api/urls/{code}/analytics` | Total clicks, per-day counts, top referrers/user-agents. |
| `PUT` | `/api/urls/{code}` | Update destination and/or expiry. |
| `DELETE` | `/api/urls/{code}` | Soft-delete (status → `DELETED`). |
| `GET` | `/actuator/health` | Health check. |

Full request/response schemas: [`service/openapi/openapi.yaml`](service/openapi/openapi.yaml).

## Key design decisions

**JdbcTemplate over JPA.** SQLite's Hibernate dialect support is inconsistent across Spring
Boot versions; plain `JdbcTemplate` against a hand-written `schema.sql` avoids that friction
entirely, at the cost of writing repository SQL by hand.

**The DB unique constraint is the real correctness backstop for short-code collisions**, not
just an application-level pre-check. Concurrent requests racing for the same generated code
are resolved by SQLite's `UNIQUE` constraint on `short_code`; the losing request catches the
constraint violation and retries with a new code. This is proven under a genuine concurrent
race by `ShortCodeCollisionConcurrencyTest` (50 threads, `CountDownLatch`-synchronized start,
shrunk code space). Empirically, `sqlite-jdbc` does not populate `SQLState`, so Spring's
generic `DuplicateKeyException` translation doesn't fire — the constraint violation is
detected by unwrapping the cause chain for `org.sqlite.SQLiteException` and checking
`getResultCode() == SQLITE_CONSTRAINT_UNIQUE` (see `SqliteConstraintViolations`).

**SQLite is single-writer.** `hikari.maximum-pool-size=1` plus WAL mode plus a `busy_timeout`
pragma serialize writes cleanly instead of surfacing spurious "database is locked" errors.

**The orchestrator is plain Java, not Python or a workflow framework.** The service is
already Java/Spring Boot; a second language for tooling would mean a second toolchain for no
real benefit, since nothing about a DAG/state-machine engine requires Python. `ExecutorService`
gives real parallel stage execution, Jackson gives a JSON audit log, SnakeYAML gives
config-driven graph/policy/approval definitions — no Airflow/Temporal needed.

**Expiry: lazy check + active sweep.** Links are always correctly treated as expired at read
time (`ShortUrl.isExpired()`), regardless of whether a background sweep has run yet.
`ExpiredLinkCleanupJob` additionally sweeps periodically and flips overdue rows to an explicit
`EXPIRED` status, so anything querying status directly (analytics, admin tooling) converges
without waiting for a read to trigger the lazy path. This was the deliberate brownfield
enhancement — see `docs/scenarios.md`.

## Prerequisites

- Java 21+ (developed against Java 21 language level; tested on JDK 25).
- Maven 3.9+ on `PATH` (no wrapper checked in — see `docs/setup.md` if you need one).
- Docker, optional, only needed for the containerized run.

## Running

From the repo root:

```
mvn -pl service -am spring-boot:run
```

The service listens on `:8080`, backed by `./service/data/urlshortener.db` (created
automatically). Override with `DB_PATH=/path/to/file.db`.

Or build and run the jar directly:

```
mvn -pl service -am package
java -jar service/target/service.jar
```

Or with Docker:

```
docker build -t url-shortener -f service/Dockerfile .
docker run -p 8080:8080 -v urlshortener-data:/app/data url-shortener
```

Running the orchestrator and its three required scenarios: see
[`docs/setup.md`](docs/setup.md) and [`docs/scenarios.md`](docs/scenarios.md).

## Tests

```
mvn test
```

Runs both modules from the repo root — `service` (35 tests: CRUD, redirect, analytics,
idempotency, rate limiting, the short-code collision concurrency test, the scheduled
cleanup job, and one deliberately-flaky fixture used to give the orchestrator's retry/MTTR
metrics real signal to report) and `orchestrator` (32 tests: graph loading/cycle detection,
parallel scheduling, retry/rollback/safe-stop, policy rules, secret scanning, ambiguity
detection and dynamic re-planning, metrics calculation, approval gate replay).

See [`docs/testing-and-tradeoffs.md`](docs/testing-and-tradeoffs.md) for what's covered,
what's deliberately out of scope, and known limitations.

## Example requests and responses

Captured from a real run of the service (not hand-written) — start it with
`mvn -pl service -am spring-boot:run` and reproduce these yourself.

Create a shortened URL:

```
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://example.com/some/very/long/path", "expiresAt": "2027-01-01T00:00:00Z"}'
```

```
HTTP/1.1 201
Content-Type: application/json

{"shortCode":"K07fFW5","shortUrl":"http://localhost:8080/K07fFW5","originalUrl":"https://example.com/some/very/long/path","status":"ACTIVE","isCustomAlias":false,"createdAt":"2026-08-09T18:38:36.798251100Z","updatedAt":"2026-08-09T18:38:36.798251100Z","expiresAt":"2027-01-01T00:00:00Z","lastAccessedAt":null}
```

Follow the redirect:

```
curl -i http://localhost:8080/K07fFW5
```

```
HTTP/1.1 302
Location: https://example.com/some/very/long/path
```

Check analytics (after the redirect above has been followed once):

```
curl http://localhost:8080/api/urls/K07fFW5/analytics
```

```json
{"shortCode":"K07fFW5","totalClicks":1,"clicksPerDay":[{"date":"2026-08-09","count":1}],"referrers":{"unknown":1},"userAgents":{"curl/8.7.1":1},"lastAccessedAt":"2026-08-09T18:38:42.265749500Z"}
```

Unknown code (404):

```
curl -i http://localhost:8080/doesnotexist
```

```
HTTP/1.1 404
Content-Type: application/json

{"timestamp":"2026-08-09T18:38:43.461214200Z","status":404,"error":"Not Found","message":"No URL found for short code 'doesnotexist'","path":"/doesnotexist"}
```

Disallowed scheme (400) — scheme validation rejects it before a row is ever created:

```
curl -i -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" -d '{"originalUrl": "javascript:alert(1)"}'
```

```
HTTP/1.1 400
Content-Type: application/json

{"timestamp":"2026-08-09T18:38:43.536893300Z","status":400,"error":"Bad Request","message":"originalUrl must use http or https scheme","path":"/api/urls"}
```

## Repository layout

```
URL-Shortener/
├── pom.xml                 # Maven reactor (modules: service, orchestrator)
├── service/                # Spring Boot URL shortener
├── orchestrator/           # SDLC orchestration engine + graph/policy config
└── docs/                   # architecture, scenarios, setup, testing/trade-offs, final summary
```
