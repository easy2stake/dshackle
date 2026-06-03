# Agent guidelines (easy2stake / `custom` branch)

High-level context for AI assistants and contributors working on this fork. This is not upstream drpcorg documentation.

## What we are doing

Run **our own fork** of dshackle, based on **v0.79.4**, with **custom features** (starting with **logging**). We **pull upstream (drpcorg) releases later** and merge them into our line **without losing our changes**. We are **not** contributing back to upstream.

## Git workflow

| Branch / remote | Role |
|-----------------|------|
| **`custom`** | Day-to-day work: v0.79.4 base + our commits |
| **`master`** | Optional mirror of `upstream/master` (no custom commits) |
| **`upstream`** | `drpcorg/dshackle` — fetch only, **never push** |
| **`origin`** | Our fork (`easy2stake/dshackle`) — optional backup / CI |

When production moves to a new **0.79.x** tag (e.g. `v0.79.5`):

```bash
git fetch upstream --tags
git switch custom
git merge v0.79.5 -m "Merge upstream release v0.79.5 into custom"
git submodule update --init --recursive
# build and test
```

Only merge **`master`** when intentionally adopting a new major/minor line (e.g. 0.80), not for routine 0.79.x prod updates.

## How to change code

- **Prefer small, focused diffs** on `custom` — one concern per change when possible (e.g. access-log timing vs Docker/GHCR publish tooling in separate commits).
- **Review before ship** — e.g. access-log config must not fail open (invalid `chains` must not silently log everything).
- **Validate by running dshackle** and inspecting real output (e.g. `access_log.jsonl`), not only by reading code.
- Match existing Kotlin/Spring style in the tree; avoid unrelated refactors.

## Custom logging (current focus)

Built-in access log (`accessLog` in YAML) writes JSON Lines per response. See `docs/06-monitoring.adoc`.

**Fork addition on `custom`:** optional `accessLog.chains` limits logging to specific blockchains (same ids as `proxy.routes` / `upstreams`, e.g. `ethereum`).

| `chains` in config | Behavior |
|--------------------|----------|
| Omitted | Log all chains (default) |
| Valid list | Log only those chains |
| Empty or only invalid ids | **No access logging**; service **still starts** (warn/error in app logs only) |

**Fork addition on `custom`:** optional `accessLog.min-latency-ms` logs only slow `NativeCall` replies (`latency >=` threshold, inclusive). Omitted or `0` = no filter; negative = disabled with warn. Other event types (`Status`, streams, etc.) are not filtered. Composes with `chains`. Filter runs in `AccessLogWriter.shouldLog` — do not regress `latency` computation.

**Per-call response time (`latency`, on `custom`):** each access-log line for `NativeCall` includes milliseconds from `request.start` to `ts`.

| Field | Meaning |
|-------|---------|
| `request.start` | Client request received: HTTP/WS = when `AccessHandlerHttp` handler is created; gRPC = when `NativeCall` RPC starts (`EventsBuilder.NativeCall` in `AccessHandlerGrpc`) |
| `ts` | When that sub-call’s reply finished (batch JSON-RPC: one line per item, not a shared close timestamp) |
| `latency` | `Duration.between(request.start, ts)` in ms |

**Code touchpoints (do not regress timing or fail-closed `chains`):**

- `monitoring/accesslog/AccessHandlerHttp.kt` — `arrivalTs`, per-id `responseTimestamps` in `onResponse`, log at `close()`
- `monitoring/accesslog/EventsBuilder.kt` — `NativeCall(requestStartTs)`; gRPC `onReply` must use `between(requestStartTs, now)` (not reversed)
- `proxy/HttpHandler.kt` / `BaseHandler.kt` — handler lifecycle only; timing logic stays in accesslog package

**Tests:** `./gradlew test --tests "io.emeraldpay.dshackle.monitoring.accesslog.*"`

**Not implemented yet (possible follow-ups):** filter by RPC method, log selected upstream id, full raw POST body.

## Containers and releases (`custom`)

Fork images are published to **GHCR** (not only Docker Hub). Keep container/CI changes separate from logging feature commits.

| Piece | Role |
|-------|------|
| `Makefile` | `jib-ghcr`, `docker-build-push`, `tag-release` |
| `scripts/docker-build-push.sh` | Local or scripted build/push; `tag-release v0.79.4` creates annotated tag `v0.79.4-log` (suffix `FORK_TAG_SUFFIX`, default `log`) |
| `.github/workflows/docker.yaml` | Build/push on `v*` tags and releases |
| `build.gradle` | `jibTargetImage` / `jibImageTags` — release tags get version + git SHA; dev builds add `t<UTC>`; no `latest` on GHCR |

Env: `DOCKER_REGISTRY`, `GHCR_TOKEN` / `GITHUB_TOKEN`, `GHCR_USERNAME`. Use `docker/Dockerfile.build` when the host has no JDK (`FORCE_DOCKER_BUILD=1`).

## References

- Monitoring / access log: `docs/06-monitoring.adoc`, `docs/reference-configuration.adoc`
- Proxy routes: `docs/03-server-config.adoc`
