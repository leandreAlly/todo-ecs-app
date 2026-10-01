# todo-ecs — application

A to-do list served from ElastiCache Redis and persisted to RDS PostgreSQL
through RDS Proxy, deployed blue/green to ECS Fargate.

Infrastructure lives in
[todo-ecs-infrastructure](https://github.com/leandreAlly/todo-ecs-infrastructure).

## What it demonstrates

`GET /api/tasks` returns the list *and* says where it came from:

```json
{ "items": [ … ], "source": "cache", "latencyMs": 2 }
```

`source` is `database` on the first read, `cache` on the next, and `database`
again immediately after any write, because every write drops the cached list.
The UI shows this as a coloured badge, so the cache is visible rather than
something to take on trust.

Cache fills carry a Redis generation number. A committed create, update or
delete atomically advances that generation and evicts the list, so a slow read
on another ECS task cannot put pre-write data back into Redis after the
invalidation. Redis remains an optimisation and PostgreSQL remains authoritative.

If Redis becomes unreachable the source reads `database (cache unavailable)`
and the application keeps serving. A cache is an optimisation: losing it
degrades latency and nothing else. Every Redis failure is swallowed, and a
small breaker stops the app paying a command timeout on every request while
the cache is down.

## Code layout

Layered by responsibility under `com.leandre.todoecs`, with `Application` at
the root so component and entity scanning cover every package. Tests mirror
the same packages.

| Package | Holds |
| --- | --- |
| `controller` | `TaskController` (`/api/tasks`, plus `/health` and `/api/version`), `DiagnosticsController` (`/api/diagnostics`) |
| `service` | `TaskService` - Redis-first reads, PostgreSQL writes, cache invalidation after commit |
| `repository` | `TaskRepository`, the Spring Data JPA repository |
| `model` | `Task`, the JPA entity |
| `dto` | Request and response records: `TaskRequest`, `TaskUpdateRequest`, `TaskView`, `TaskPage` |
| `cache` | `TaskCache` and its Redis and no-op implementations |
| `config` | Cache selection and startup warm-up |
| `exception` | `ApiExceptionHandler`, which maps failures to JSON error responses |

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | The UI |
| `GET` | `/health` | Liveness. **Shallow by design** — the ALB health check for both target groups. It touches neither the database nor the cache, so a brief RDS failover cannot drain the fleet. |
| `GET` | `/api/version` | Running version and commit. Polled by the UI to visualise a blue/green traffic shift. |
| `GET` | `/api/diagnostics` | Deep check of the database and the cache. Always returns 200, with per-dependency state in the body. |
| `GET` | `/api/tasks` | List, with `source` and `latencyMs` |
| `POST` | `/api/tasks` | Create |
| `PATCH` | `/api/tasks/{id}` | Update title and/or completed |
| `DELETE` | `/api/tasks/{id}` | Delete |

## Configuration

Everything comes from the environment; the task definition supplies it all.

| Variable | Notes |
| --- | --- |
| `SPRING_DATASOURCE_URL` | Points at the **RDS Proxy** endpoint, never the instance. |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Injected by ECS from Secrets Manager. |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | ElastiCache primary endpoint. Must be the DNS name — TLS verifies the hostname. |
| `SPRING_DATA_REDIS_SSL_ENABLED` | `true` in AWS; the replication group has encryption in transit. |
| `APP_CACHE_ENABLED`, `APP_CACHE_TTL_SECONDS` | Cache behaviour. |
| `APP_VERSION`, `APP_COMMIT` | Baked into the image at build time. |

### Talking to RDS Proxy

The JDBC URL carries four parameters that are not decoration:

```
?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
&assumeMinServerVersion=9.0&prepareThreshold=0&tcpKeepAlive=true
```

The proxy is created with `RequireTLS`, so TLS is mandatory. `verify-full`
actually checks the certificate — the proxy presents an ACM certificate, and
`DefaultJavaSSLFactory` points pgjdbc at the JVM trust store rather than the
`~/.postgresql/root.crt` it looks for by default.

`assumeMinServerVersion` and `prepareThreshold=0` exist to stop the proxy
*pinning* a session to one backend connection. The proxy pins whenever it sees
session state it cannot reason about, and pgjdbc creates named server-side
prepared statements after five executions. A pinned session is a connection
the pool cannot reuse, which would defeat the point of having a proxy at all.

Hikari is deliberately small (`maximum-pool-size=5`) for the same reason: the
proxy is the pool. `initialization-fail-timeout=-1` starts it empty, so a
database blip during a green launch cannot fail the application context and
with it the deployment.

## Running it locally

```sh
docker network create todo && \
docker run -d --name pg --network todo \
  -e POSTGRES_DB=todo -e POSTGRES_USER=todoadmin -e POSTGRES_PASSWORD=local postgres:17-alpine && \
docker run -d --name redis --network todo redis:7-alpine && \
docker build -t todo-ecs-app:local . && \
docker run --rm --network todo -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://pg:5432/todo' \
  -e SPRING_DATASOURCE_USERNAME=todoadmin -e SPRING_DATASOURCE_PASSWORD=local \
  -e SPRING_DATA_REDIS_HOST=redis -e SPRING_DATA_REDIS_SSL_ENABLED=false \
  todo-ecs-app:local
```

Then `curl localhost:8080/api/tasks` twice and watch `source` change.

## Tests

`mvn test` needs neither a database nor Redis, because CI has neither. The
controller test is a `@WebMvcTest` slice with the service mocked, and the
service test is plain JUnit against an in-memory `TaskCache`. There is
deliberately no `@SpringBootTest`: it would try to build a `DataSource` and
fail on the runner.

`TaskViewJsonTest` guards the quietest failure mode in the codebase. If the
cache serialiser were handed an `ObjectMapper` without `JavaTimeModule`, every
write would throw on the `Instant` fields, the exception would be swallowed as
a cache failure, and the cache would silently never serve a hit.

## How a push becomes a deployment

The workflow runs the tests, builds one image and pushes it as `latest` — the
only tag in the repository. ECR moves the tag to the new image and leaves the
previous one untagged. The commit SHA is baked into the image as a build
argument, so `/api/version` and the UI still report which commit is live.

The description lives in this repository as two committed files:

- **`taskdef.json`**, with `"image": "<IMAGE1_NAME>"` as a literal placeholder
- **`appspec.yaml`**, with the usual `<TASK_DEFINITION>` placeholder

The push of `latest` emits the EventBridge event. The rule starts the pipeline
with the event's image digest as the ECR source override, and the
CodeConnections source reads these two files from the head of `main`. A
CodeBuild stage replaces the infrastructure placeholders in `taskdef.json`
with values supplied directly by CloudFormation, including the full generated
Secrets Manager ARNs. CodePipeline then substitutes the digest for
`<IMAGE1_NAME>` and CodeDeploy registers the revision.

The digest keeps a later move of `latest` from changing an in-flight
deployment. The registered task definition is account- and
Region-portable in Git while every deployed image is digest-pinned.

### Required Actions variables and secrets

Set by `scripts/sync-app-vars.sh` in the infrastructure repository. Two values,
down from a dozen, because the workflow only needs to reach the registry now.

Secret — a role ARN carries the account ID, and GitHub redacts a secret from
the workflow log: `AWS_ECR_ROLE_ARN`.

Variables: `AWS_REGION`, `ECR_REPOSITORY`.
