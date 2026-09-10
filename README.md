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

If Redis becomes unreachable the source reads `database (cache unavailable)`
and the application keeps serving. A cache is an optimisation: losing it
degrades latency and nothing else. Every Redis failure is swallowed, and a
small breaker stops the app paying a command timeout on every request while
the cache is down.

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

The workflow runs the tests, builds one image and tags it twice — with the
commit SHA and with `latest` — uploads `taskdef.json` and `appspec.yaml` to the
artifact bucket, then pushes the SHA tag followed by `latest`.

That order is load-bearing. The pipeline's S3 source does not poll; the only
trigger is an EventBridge rule matching a push of `latest`. Publishing the
config first guarantees the deployment picks up a task definition that already
names the new image.

The task definition references the **SHA** tag. A rollback therefore returns to
the image that was actually running, rather than to whatever `latest` has since
become — which is what would happen if the task definition named a mutable tag.

### Required Actions variables

Set by `scripts/sync-app-vars.sh` in the infrastructure repository; all of them
are outputs of the root stack.

`AWS_REGION`, `AWS_ECR_ROLE_ARN`, `ECR_REPOSITORY`, `ARTIFACT_BUCKET`,
`TASK_FAMILY`, `TASK_EXEC_ROLE_ARN`, `TASK_ROLE_ARN`, `LOG_GROUP`, `DB_URL`,
`DB_SECRET_ARN`, `REDIS_HOST`, `REDIS_PORT`.

An unset `ARTIFACT_BUCKET` is the signal that the delivery stack does not exist
yet — on that first run the workflow publishes the image only, because the
platform cannot be created until an image exists to pull.
