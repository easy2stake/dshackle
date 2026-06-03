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

Chain filter is enforced in `AccessLogWriter.shouldLog` and early in `AccessHandlerHttp` via `AccessLogConfig.shouldLog(chain)` (skip building events for excluded chains).

**Fork addition on `custom`:** optional `accessLog.min-latency-ms` logs only slow `NativeCall` replies (`latency >=` threshold, inclusive). Omitted or `0` = no filter; negative = disabled with warn. Other event types (`Status`, streams, etc.) are not filtered. Composes with `chains`. Filter runs in `AccessLogWriter.shouldLog` — do not regress `latency` computation.

**Fork addition on `custom`:** optional `accessLog.include-request-bodies` and `accessLog.include-response-bodies` control payload logging independently (both default `false`). Request params go to `nativeCall.requestParams`; response payloads and error text go to `responseBody` / `errorMessage`. Legacy `include-messages: true` enables both (deprecated; granular keys override when set).

| Flag | JSON field | Default |
|------|------------|---------|
| `include-request-bodies` | `nativeCall.requestParams` | off |
| `include-response-bodies` | `responseBody`, `errorMessage` | off |
| `include-messages: true` (legacy) | both of the above | — |

**Per-call response time (`latency`, on `custom`):** each access-log line for `NativeCall` includes milliseconds from `request.start` to `ts`.

| Field | Meaning |
|-------|---------|
| `request.start` | Client request received: HTTP/WS = when `AccessHandlerHttp` handler is created; gRPC = when `NativeCall` RPC starts (`EventsBuilder.NativeCall` in `AccessHandlerGrpc`) |
| `ts` | When that sub-call’s reply finished (batch JSON-RPC: one line per item, not a shared close timestamp) |
| `latency` | `Duration.between(request.start, ts)` in ms |

**Fork addition on `custom`:** `NativeCall` lines include `upstreamId` and `upstreamNodeVersion` when the upstream provides them (HTTP from `CallResult`; gRPC from reply items).

**Code touchpoints (do not regress timing or fail-closed `chains`):**

- `monitoring/accesslog/AccessHandlerHttp.kt` — `arrivalTs`, per-id `responseTimestamps` in `onResponse`, log at `close()`; HTTP/WS latency uses `between(requestStartTs, replyTs)`
- `monitoring/accesslog/EventsBuilder.kt` — `NativeCall(requestStartTs)`; gRPC protobuf `onReply` uses `between(requestStartTs, Instant.now())` (not reversed)
- `monitoring/accesslog/AccessHandlerGrpc.kt` — wires gRPC `NativeCall` to `EventsBuilder`
- `proxy/HttpHandler.kt` / `BaseHandler.kt` — handler lifecycle only; timing logic stays in accesslog package

**Tests:** `./gradlew test --tests "io.emeraldpay.dshackle.monitoring.accesslog.*"`

**Not implemented yet (possible follow-ups):** filter by RPC method, filter or select upstream id in config, full raw POST body.

## Containers and releases (`custom`)

Fork images are published to **GHCR** via `scripts/release.sh` (day-to-day). Upstream-style **Docker Hub** publish still exists via `make jib` and `.github/workflows/publish.yaml` on GitHub releases. Keep container/CI changes separate from logging feature commits.

| Piece | Role |
|-------|------|
| `scripts/release.sh` | **Primary GHCR workflow:** `verify` (check HEAD contains upstream tag), `prod` (fork tag `vX.Y.Z-log`, push tag, publish), `dev` (snapshot + `t<UTC>` + SHA), `publish` (custom `--tags`) |
| `Makefile` | `jib` / `jib-docker` with `-Pdocker=drpcorg` (Docker Hub–style registry id, not GHCR by default) |
| `.github/workflows/publish.yaml` | On release: `make jib` → Docker Hub; `make distZip` → GitHub release asset |
| `build.gradle` `jib` block | Base image `drpc-dshackle`; target from `-Pdocker=<registry>`; tags via `-Djib.to.tags=` (as in `release.sh` for GHCR) |

**`scripts/release.sh` examples:**

```bash
./scripts/release.sh verify --upstream-tag v0.79.4
./scripts/release.sh prod --upstream-tag v0.79.4          # tag v0.79.4-log, push, publish to ghcr.io/<origin-owner>/dshackle
./scripts/release.sh dev                                  # dev tags: <next>-SNAPSHOT, t<UTC>, <sha>
./scripts/release.sh publish --tags 0.79.4-log,abc1234
```

Auth: `gh auth refresh -h github.com -s write:packages` (or `GITHUB_TOKEN` with package write). The script maps that token into Jib’s registry auth. Optional: `--suffix log` (default), `--platform linux/amd64`, `--dry-run`, `--allow-dirty`.

`docker/Dockerfile.build` — builder image for environments without a host JDK (used when extending local build scripts; `release.sh` expects local Java 21 + Docker for `drpc-dshackle`).

## References

### Access log (config & docs)

| Topic | Where |
|-------|--------|
| Overview, sample YAML, JSON line format | `docs/06-monitoring.adoc` — *Access / Request Log* |
| All `accessLog.*` options (`enabled`, `filename`, `include-request-bodies`, `include-response-bodies`, `include-messages`, `chains`, `min-latency-ms`) | `docs/reference-configuration.adoc` — `[#accessLog]` |
| Top-level Example (includes `accessLog` block) | `docs/reference-configuration.adoc` — *Example* |
| YAML → config object | `src/main/kotlin/io/emeraldpay/dshackle/config/AccessLogReader.kt`, `AccessLogConfig.kt` |
| Config parsing tests (inline YAML fixtures) | `src/test/groovy/io/emeraldpay/dshackle/config/AccessLogReaderSpec.groovy` |
| Runtime / filter / latency tests | `src/test/groovy/io/emeraldpay/dshackle/monitoring/accesslog/` |
| Fork behavior (`chains`, `min-latency-ms`, `latency`, body flags, upstream fields) | *Custom logging* section above |

Minimal enable:

```yaml
accessLog:
  enabled: true
  filename: /var/log/dshackle/access_log.jsonl
```

Fork demo (chain filter + slow-call filter):

```yaml
accessLog:
  enabled: true
  filename: /var/log/dshackle/access_log.jsonl
  chains:
    - ethereum
    - bitcoin
  min-latency-ms: 500
```

Debug payloads (response bodies off by default):

```yaml
accessLog:
  enabled: true
  filename: /var/log/dshackle/access_log.jsonl
  include-request-bodies: true
  include-response-bodies: true
```

### Metrics & monitoring

| Topic | Where |
|-------|--------|
| Prometheus setup, Grafana, tracing | `docs/06-monitoring.adoc` |
| All `monitoring.*` / `prometheus.*` options | `docs/reference-configuration.adoc` — `[#monitoring]` |
| Health checks overview | `docs/06-monitoring.adoc` — *Health Checks* |
| All `health.*` options | `docs/reference-configuration.adoc` — `[#health]` |
| Test fixture config | `src/test/resources/configs/dshackle-monitoring-basic.yaml` |
| Grafana dashboard JSON | `dashboard/dshackle.json` |
| Default metrics URL (when `monitoring` unset) | `http://127.0.0.1:8081/metrics` |

Example:

```yaml
monitoring:
  enabled: true
  jvm: false
  extended: false
  prometheus:
    enabled: true
    bind: 192.168.0.1
    port: 8000
    path: /status/prometheus
```

### Other

- Proxy routes: `docs/03-server-config.adoc`
- Docs index: `docs/README.adoc` → *Logging & Monitoring*
