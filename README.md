<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-light.svg" alt="Orazaka" width="96">
</picture>

# Orazaka Studio

**The AI that never leaves home.**

Studio & pack marketplace: pack catalogue, studio blueprints, installations and saga-driven runs, with its contract (studio-api) and HTTP client (studio-client).

[![CI](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/orazaka-studio/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/orazaka-studio)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f67e23)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/orazaka)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Orazaka AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

| Capability | Endpoint |
|:---|:---|
| Pack catalogue & bundles | `/api/v1/studios/packs` · `/api/v1/studios/packs/bundles` |
| Studios & blueprints (semver'd DAGs) | `/api/v1/studios` · `/api/v1/studios/{key}/blueprints` |
| Installations | `/api/v1/studios/{key}/installations` |
| Runs (saga, approvals, cancel) | `/api/v1/studios/runs` |
| Admin runtime settings | `GET /api/v1/studios/admin/runtime-config` · `PUT /api/v1/studios/admin/runtime-config/{key}` |

| Module | Role |
|:---|:---|
| `orazaka-studio-api` | Tier-1 contract. |
| `orazaka-studio-client` | HTTP client used by the engine (`orazaka-business`). |
| `orazaka-studio-service` | Spring Boot host (port `8096`), transactional outbox to the job plane. |
| `infra/initdb/80-studio.sql` | Schema & role of `orazaka_studio_db` (content comes from [orazaka-packs](https://github.com/krizaka/orazaka-packs)). |

## Position in the platform

| | |
|:---|:---|
| Depends on | [`orazaka-build`](https://github.com/krizaka/orazaka-build) · [`orazaka-contracts`](https://github.com/krizaka/orazaka-contracts) · [`krizaka-billing`](https://github.com/krizaka/krizaka-billing) |
| Used by | [`orazaka-ai-engine`](https://github.com/krizaka/orazaka-ai-engine) |
| Workspace path | `orazaka-apps/services/orazaka-studio` |

## Build

**Inside the Orazaka workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/orazaka.git && cd orazaka
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-studio/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/orazaka#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Saga runtime controls

Published blueprint versions are cached per service instance; the live `run.blueprint-cache-size`
row in `studio_runtime_config` bounds the cache (`0` disables it). The same table controls
`run.outcome-listener.concurrency`; changes are applied while the service is running, and outcome
consumers run on virtual threads. Step state, failure cause, and output rows are read together for
each advance. The five-step/20-item fan-out benchmark is `RunOverheadMeasurementIT` and writes its
latencies, JFR profile, and `EXPLAIN ANALYZE` plans for the job lookup and step snapshot to
`target/m1-saga-hot-path.txt` and `target/m1-saga-hot-path.jfr`.

## Admin runtime settings

Both runtime-config endpoints require the `ADMIN` role. The list response includes each setting's
typed current value, type, description, default, and persisted bounds. Replace a value with a JSON
value matching its type: integer settings take a JSON number, booleans take `true`/`false`, and list
settings take an array of strings. For example:

```http
PUT /api/v1/studios/admin/runtime-config/run.fan-out-max
Content-Type: application/json

{"value": 12}
```

Updates are audited with the administrator id and before/after values. Runtime reads query the
database directly, so a successful update is effective on the next run without a service restart.

A fresh database receives the schema and bounds from `infra/initdb/80-studio.sql`. An existing one (dev)
is migrated from the workspace, additive and idempotent: `node scripts/dev-deploy.mjs runtime-config-admin`
([krizaka/orazaka](https://github.com/krizaka/orazaka), `infra/migrations/2026-10-23-studio-runtime-config-admin.sql`).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

---

<sub>A Krizaka product · [krizaka.com](https://www.krizaka.com) · Apache-2.0</sub>
