# Distributed Code Judge

A backend that automates execution and evaluation of user-submitted source code — like an
online judge (LeetCode/Codeforces-style) — built around a distributed, asynchronous
architecture with real Docker sandboxing, not a single-process demo.

A stateless `api-gateway` role accepts submissions and publishes them to Kafka; one or more
`execution-worker` replicas consume from a shared consumer group and compile/run submitted
code in hardened, ephemeral Docker containers per test case.

## Features

- Validated submission intake with a `429 Retry-After` rate-limit response, enforced via a
  Redis-backed hybrid token bucket + sliding window (shared across every gateway replica).
- Kafka-backed submission queue (`submissions` topic, 6 partitions) with a `judge-workers`
  consumer group — `execution-worker` scales horizontally with
  `docker-compose up --scale execution-worker=N`, and jobs that keep failing retry a few times
  before landing on a `submissions-dlq` dead-letter topic instead of getting stuck forever.
- Persistent problems, hidden test cases, submissions, state, verdict, runtime, and memory,
  with Flyway-managed schema migrations against Postgres.
- Real Docker-based sandbox execution across 4 languages (Java, Python, C, C++): code is
  compiled (where applicable) and run per test case inside a network-isolated,
  memory/CPU/PID-limited, read-only-rootfs container, with genuine `ACCEPTED` /
  `WRONG_ANSWER` / `TIME_LIMIT_EXCEEDED` / `MEMORY_LIMIT_EXCEEDED` / `COMPILE_ERROR` /
  `RUNTIME_ERROR` detection — verified end to end against all four languages, see
  `ERROR_LOG.md`.
- Redis-backed session tokens (TTL-expiring, shared across gateway replicas) instead of a
  single-JVM in-memory map.
- Prometheus-compatible metrics through Spring Actuator.
- Interactive frontend served from Spring Boot at `/`.

## Tech Stack

| Category | Technology | Role |
|---|---|---|
| Backend | Java 21 / Spring Boot 3 | API gateway, service orchestration, business logic |
| State / Rate Limit | Redis | Sessions, atomic Lua-script rate limiting |
| Messaging | Apache Kafka | Async job pipeline, decouples gateway from workers |
| Sandboxing | Docker (via `docker-java`) | Isolated execution environment for untrusted code |
| Database | PostgreSQL + Flyway | Persistent storage for users, problems, submissions |
| Deployment | Docker Compose | Local/self-hosted orchestration of all services |

## Run Locally (single-process, no infra)

Install Maven, then run:

```bash
mvn spring-boot:run
```

Open:

```text
http://localhost:8080
```

With no Spring profile set, the app runs both the gateway and worker roles in one process against in-memory H2, and lazily connects to Redis/Kafka/Docker only when those features are actually exercised — so it still boots without any of that infrastructure running, but submissions won't reach a terminal state until a broker is reachable.

## Run the Full Distributed Stack

```bash
docker-compose up --build
```

This starts `postgres`, `redis`, `zookeeper`, `kafka`, one `api-gateway` (profile `postgres,gateway`), and one `execution-worker` (profile `postgres,worker`, with the host's Docker socket mounted read-only so it can launch sandbox containers). To scale execution horizontally:

```bash
docker-compose up --build --scale execution-worker=3
```

Every worker replica joins the same `judge-workers` Kafka consumer group, so Kafka spreads the `submissions` topic's partitions across them automatically.

## API

```text
POST /api/auth/email
POST /api/auth/password
GET  /api/auth/me
GET  /api/problems
GET  /api/problems/{id}
POST /api/submissions
GET  /api/submissions/{id}
GET  /api/submissions
GET  /api/judge/metrics
GET  /actuator/prometheus
```

Spring Security protects all judge APIs except the sign-in endpoints and static frontend assets. After signing in, send the returned token as:

```text
Authorization: Bearer <token>
```

`GET /api/submissions/{id}` is restricted to the submission's owner (403 otherwise). `POST /api/auth/email` only works for accounts that don't have a password set — accounts created with a password must sign in with `/api/auth/password`.

Example submission:

```json
{
  "problemId": 1,
  "language": "JAVA",
  "sourceCode": "import java.util.*;\npublic class Main { public static void main(String[] args) { Scanner sc = new Scanner(System.in); System.out.println(sc.nextInt() + sc.nextInt()); } }"
}
```

## Architecture Notes

- **Role split**: `JudgeController`/`AuthController`/`SubmissionService`/`RateLimiterService`/`AuthService` are gateway-only (`@Profile("!worker")`); `ExecutionWorker`/`SandboxExecutor`/the Docker client are worker-only (`@Profile("!gateway")`). With no profile active, both sets load in the same process — that's what makes local single-process dev work.
- **Sandbox file transfer**: `SandboxExecutor` talks to the Docker daemon via the socket mounted into `execution-worker`. Because the worker itself runs in a container, it can't use host bind-mounts to get source files into the sandbox container (a path inside the worker isn't resolvable by the host daemon), and Docker's copy-to-container API refuses to write into a `--read-only` container even on a writable mount. Source code and per-test-case stdin are instead written via `docker exec` running a small base64-decode command, and the program under test is run with shell input redirection (`< input.txt`) — not live stdin-attach, which hangs indefinitely on this Docker client version (see `ERROR_LOG.md` #13).
- **Sandbox hardening**: `--network none`, memory + CPU + PID limits, read-only root filesystem with only a `tmpfs` `/workspace` writable (mounted `exec`, since tmpfs defaults to `noexec` and would silently block running compiled C/C++ binaries), non-root user. Base images: `eclipse-temurin:21-jdk-alpine` (Java), `python:3.12-alpine` (Python), `gcc:14-bookworm` (C/C++). Images are pulled on first use per worker if not already present.
- **Timeout detection**: the in-container `timeout` wrapper's exit code isn't consistent across base images — GNU coreutils reports `124`, but Alpine's BusyBox `timeout` (as shipped in the Python image) reports `143` instead. Both are treated as `TIME_LIMIT_EXCEEDED`.
- **Migrations**: `src/main/resources/db/migration/V1__baseline.sql` (Flyway), used only under the `postgres` profile; the default H2 profile still uses `ddl-auto: create-drop` for fast local iteration.

## Security

- Non-root user inside every sandbox container; submitted code runs with no network access
  (`--network none`) and hard memory/CPU/PID limits enforced at the cgroup level.
- Bearer-token sessions are Redis-backed with a TTL (`judge.session-ttl-hours`, default 24h) —
  not an in-memory map, so they survive gateway restarts and work across replicas.
- Password hashing via PBKDF2WithHmacSHA256 (120k iterations, per-user salt, constant-time
  compare).
- Submission ownership is enforced: `GET /api/submissions/{id}` returns `403` for anyone but
  the submitter.
- Rate limiting is atomic and shared across all gateway replicas via a Redis Lua script
  (`redis-rate-limiter.lua`), not per-instance state.

## Monitoring

Prometheus metrics are exposed at `/actuator/prometheus`, including:

| Metric | Type | Description |
|---|---|---|
| `judge_worker_latency_ms` | Histogram | End-to-end execution latency per submission |
| `judge_rate_limit_rejections` | Counter | Requests rejected by the rate limiter |

`GET /api/judge/metrics` also returns a lightweight JSON snapshot (submission counts by
state/verdict, rate-limit rejections) for the frontend dashboard.

## Docs

- [`ERROR_LOG.md`](ERROR_LOG.md) — real bugs hit while building this, root cause and fix for each.
- [`GOOGLE_AUTH_PLAN.md`](GOOGLE_AUTH_PLAN.md) — implementation plan for adding Google sign-in.
