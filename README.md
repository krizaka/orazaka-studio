<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-light.svg" alt="Orazaka" width="96">
</picture>

# Orazaka Studio

**The AI that never leaves home.**

Studio & pack marketplace: pack catalogue, studio blueprints, installations and saga-driven runs, with its contract (studio-api) and HTTP client (studio-client). A step's output is held to the contract it declares (ADR-076); a `orazaka.core.research.grounded` step that declares none is held to the citation shape of ADR-087 (`CitationContract`): an output outside it fails the step as `EXECUTOR_FAULT`.

[![CI](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/orazaka-studio/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/orazaka-studio)
[![License: FSL-1.1-ALv2](https://img.shields.io/badge/license-FSL--1.1--ALv2-blue.svg)](LICENSE.md)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f67e23)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/orazaka)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=coverage)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Orazaka AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** FSL-1.1-ALv2 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

| Capability | Endpoint |
|:---|:---|
| Pack catalogue & bundles | `/api/v1/studios/packs` · `/api/v1/studios/packs/bundles` |
| Studios & blueprints (semver'd DAGs) | `/api/v1/studios` · `/api/v1/studios/{key}/blueprints` |
| Installations | `/api/v1/studios/{key}/installations` |
| Runs (saga, approvals, cancel) | `/api/v1/studios/runs` |
| Admin runtime settings | `GET /api/v1/studios/admin/runtime-config` · `PUT /api/v1/studios/admin/runtime-config/{key}` · `GET /api/v1/studios/admin/runtime-config/audit?key=&limit=` |

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

`run.result-cache.studios` lists the Studios whose identical run (same actor, Studio version, inputs and
installation configuration) answers from the recorded result: no hold, no dispatch, no model call, and
`cachedFrom` names the run that computed it (orazaka-studio#61). The schema ships it with
`compliance-check` (ADR-089 §4): its ruleset is resolved from the document's kind and `asOf`, so the
recorded report, judgment included, is the answer again. A new pack version is a new Studio version
and computes afresh. A step whose answer depends on more than the inputs returns `resultCacheable:
false` in its output (compliance-check, when its day came from the clock rather than from `asOf` or
the document): the run then keeps no fingerprint and is never served from the cache.

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

Updates are audited with the administrator id and before/after values; `GET …/runtime-config/audit` reads them back, newest first (one setting with `key`, at most 200). Runtime reads query the
database directly, so a successful update is effective on the next run without a service restart.

A fresh database receives the schema and bounds from `infra/initdb/80-studio.sql`. An existing one (dev)
is migrated from the workspace, additive and idempotent: `node scripts/dev-deploy.mjs runtime-config-admin`
([krizaka/orazaka](https://github.com/krizaka/orazaka), `infra/migrations/2026-10-23-studio-runtime-config-admin.sql`).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

[FSL-1.1-ALv2](LICENSE.md) (Functional Source License, Apache-2.0 future licence) © Technologies Krizaka Inc. — read,
run, modify and self-host it for any purpose except offering a competing service; each version becomes Apache-2.0 two
years after its release. Commits before the licence change remain [Apache-2.0](LICENSE-APACHE) — see [NOTICE](NOTICE)
and [ADR-012](https://github.com/krizaka/.github/blob/main/docs/adr/ADR-012-licensing-libraries-open-products-fair-source.md).
The Krizaka names and birds are not licensed: [trademark policy](https://github.com/krizaka/.github/blob/main/TRADEMARKS.md).
