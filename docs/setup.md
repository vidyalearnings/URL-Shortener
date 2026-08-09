# Setup

## Prerequisites

- Java 21+ (developed against Java 21 language level, verified on JDK 25).
- Maven 3.9+ on `PATH`. No wrapper is checked into this repo — if you don't have Maven
  installed, download it from https://maven.apache.org/download.cgi, extract it anywhere,
  and add its `bin/` directory to `PATH` for your shell session.
- Docker, optional — only needed for the containerized run of `service`.

## Build everything

From the repo root:

```
mvn clean test
```

Builds and tests both modules (`service`: 35 tests, `orchestrator`: 34 tests) in one pass.

## Run the service

```
mvn -pl service -am spring-boot:run
```

Listens on `:8080`. The SQLite file is created automatically at `./service/data/urlshortener.db`
relative to wherever the JVM is started (override with the `DB_PATH` environment variable).

Or build and run the jar directly:

```
mvn -pl service -am package
java -jar service/target/service.jar
```

Or via Docker (build context must be the repo root, since the image needs the reactor `pom.xml`):

```
docker build -t url-shortener -f service/Dockerfile .
docker run -p 8080:8080 -v urlshortener-data:/app/data url-shortener
```

## Build and run the orchestrator

```
mvn -pl orchestrator -am package
```

Produces `orchestrator/target/orchestrator.jar` (a shaded/fat jar — no separate classpath
setup needed).

### Run a scenario

**On Windows/PowerShell**, use the wrapper scripts in `orchestrator/scripts/` — pasting the
long one-liners below into some terminals (e.g. legacy Windows Console Host) can silently
split them across multiple commands. Run from the repo root:

```powershell
.\orchestrator\scripts\run-greenfield.ps1
.\orchestrator\scripts\run-brownfield.ps1
.\orchestrator\scripts\run-ambiguous.ps1
```

Add `-Interactive` to any of them to be prompted on stdin for each approval decision instead
of replaying the pre-supplied fixture, e.g. `.\orchestrator\scripts\run-ambiguous.ps1 -Interactive`.

The equivalent raw command (any shell, including bash/macOS/Linux):

```
java -jar orchestrator/target/orchestrator.jar run-scenario \
  --graph orchestrator/src/main/resources/graphs/graph.yaml \
  --requirements-input orchestrator/scenarios/inputs/requirements-greenfield.md \
  --approvals orchestrator/scenarios/approvals/greenfield-approvals.yaml \
  --run-dir orchestrator/runs/greenfield \
  --service-repo service
```

All three scenarios that ship with this repo (see `docs/scenarios.md`):

```
# Greenfield - full graph, well-specified requirements
java -jar orchestrator/target/orchestrator.jar run-scenario \
  --graph orchestrator/src/main/resources/graphs/graph.yaml \
  --requirements-input orchestrator/scenarios/inputs/requirements-greenfield.md \
  --approvals orchestrator/scenarios/approvals/greenfield-approvals.yaml \
  --run-dir orchestrator/runs/greenfield --service-repo service

# Brownfield - trimmed graph, no requirements input needed
java -jar orchestrator/target/orchestrator.jar run-scenario \
  --graph orchestrator/src/main/resources/graphs/graph-brownfield.yaml \
  --approvals orchestrator/scenarios/approvals/brownfield-approvals.yaml \
  --run-dir orchestrator/runs/brownfield --service-repo service

# Ambiguous - full graph, deliberately vague requirements input
java -jar orchestrator/target/orchestrator.jar run-scenario \
  --graph orchestrator/src/main/resources/graphs/graph.yaml \
  --requirements-input orchestrator/scenarios/inputs/requirements-ambiguous.md \
  --approvals orchestrator/scenarios/approvals/ambiguous-approvals.yaml \
  --run-dir orchestrator/runs/ambiguous --service-repo service
```

Each run writes `audit.jsonl` and `report.md` into its `--run-dir`. Pass `--interactive`
instead of `--approvals <file>` to be prompted on stdin for each approval decision in real
time, instead of replaying a pre-supplied fixture.

### Inspect a completed run

```
java -jar orchestrator/target/orchestrator.jar metrics --run orchestrator/runs/greenfield
java -jar orchestrator/target/orchestrator.jar report  --run orchestrator/runs/greenfield
```

`metrics` prints the reliability-metrics table (success rate, retry/rollback frequency, MTTR,
end-to-end and per-stage latency). `report` prints and writes `report.md` (a full timeline of
every stage transition, decision, and approval).

## Running the orchestrator's own test suite

```
mvn -pl orchestrator -am test
```

Covers graph loading/cycle detection, real parallel scheduling, retry/backoff/rollback/
safe-stop (via synthetic failing stages), all 3 policy rules, the secret scanner, the
ambiguity scanner and live dynamic re-planning, metrics calculation against a fixture audit
log, and approval-gate replay mode.
