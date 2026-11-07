<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/sovkora-light.svg" alt="Sovkora" width="96">
</picture>

# Sovkora Studio

**The AI that never leaves home.**

Studio & pack marketplace: pack catalogue, studio blueprints, installations and saga-driven runs, with its contract (studio-api) and HTTP client (studio-client). A step's output is held to the contract it declares (ADR-076); a `orazaka.core.research.grounded` step that declares none is held to the citation shape of ADR-087 (`CitationContract`): an output outside it fails the step as `EXECUTOR_FAULT`.

[![CI](https://github.com/krizaka/sovkora-studio-service/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/sovkora-studio-service/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/sovkora-studio-service/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/sovkora-studio-service)
[![License: FSL-1.1-ALv2](https://img.shields.io/badge/license-FSL--1.1--ALv2-blue.svg)](LICENSE.md)
[![Sovkora](https://img.shields.io/badge/part%20of-Sovkora-f67e23)](https://github.com/krizaka/sovkora#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/sovkora)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=krizaka_orazaka-studio&metric=coverage)](https://sonarcloud.io/summary/new_code?id=krizaka_orazaka-studio)

[Documentation](https://www.krizaka.com/en/products/sovkora) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Sovkora AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** FSL-1.1-ALv2 ·
part of the [Sovkora platform](https://github.com/krizaka/sovkora) by [Krizaka](https://krizaka.com)

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
| `sovkora-studio-api` | Tier-1 contract. |
| `sovkora-studio-client` | HTTP client used by the engine (`sovkora-business`). |
| `sovkora-studio-service` | Spring Boot host (port `8096`), transactional outbox to the job plane. |
| `sovkora-studio-service/src/main/resources/db/migration` | Schema of `orazaka_studio_db`, Flyway: `V1__studio_baseline.sql`, applied at startup (the catalogue content comes from [sovkora-packs](https://github.com/krizaka/sovkora-packs)). |
| `infra/initdb/80-studio.sql` | Local bootstrap only: the role and the database. |

## Position in the platform

| | |
|:---|:---|
| Depends on | [`sovkora-build`](https://github.com/krizaka/sovkora-build) · [`sovkora-contracts`](https://github.com/krizaka/sovkora-contracts) · [`krizaka-billing`](https://github.com/krizaka/krizaka-build/tree/main/krizaka-billing) |
| Used by | [`sovkora-engine`](https://github.com/krizaka/sovkora-engine) |
| Workspace path | `orazaka-apps/services/orazaka-studio` |

## Build

**Inside the Sovkora workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/sovkora.git && cd sovkora
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-studio/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/sovkora#consuming-packages)):

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
`cachedFrom` names the run that computed it (sovkora-studio-service#61). The schema ships it with
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

A fresh database receives the schema and bounds from Flyway (`db/migration/V1__studio_baseline.sql`, applied by the service at startup as the
database owner; the migrator connection is `DATABASE_STUDIO_MIGRATOR_URL` / `_USER` / `_PASSWORD`, defaulting to the datasource). An existing one (dev)
is adopted at version 1 (`baseline-on-migrate`, only until its first reset); until then it is migrated from the workspace, additive and idempotent: `node scripts/dev-deploy.mjs runtime-config-admin`
([krizaka/sovkora](https://github.com/krizaka/sovkora), `infra/migrations/2026-10-23-studio-runtime-config-admin.sql`).

## Governance

This repository follows the Sovkora governance contract — [AGENTS.md](https://github.com/krizaka/sovkora/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

<!-- krizaka:workflows:start -->
## Workflows

What runs in the Actions tab of this repository.

- **CI** (`ci.yml`): Lint, tests and build of the repository. Runs on push, on every pull request, on demand.
- **Dependabot auto-merge** (`dependabot-automerge.yml`): Auto-merges safe Dependabot updates. Runs on every pull request.
- **Merge resolution** (`merge-resolution.yml`): Fails a PR that drops what main added without a deletion-explained line. Runs on every pull request.
- **Release notes** (`release-notes.yml`): Writes the release notes. Runs on push.
- **Scorecard** (`scorecard.yml`): OpenSSF Scorecard of the repository. Runs when branch rules change, on a schedule, on demand.
- **Security scan** (`security.yml`): Secret scan (gitleaks) and workflow scan (zizmor); both are required checks. Runs on every pull request, in the merge queue, on push.
<!-- krizaka:workflows:end -->

## License

[FSL-1.1-ALv2](LICENSE.md) (Functional Source License, Apache-2.0 future licence) © Technologies Krizaka Inc. — read,
run, modify and self-host it for any purpose except offering a competing service; each version becomes Apache-2.0 two
years after its release. Commits before the licence change remain [Apache-2.0](LICENSE-APACHE) — see [NOTICE](NOTICE)
and [ADR-012](https://github.com/krizaka/.github/blob/main/docs/adr/ADR-012-licensing-libraries-open-products-fair-source.md).
The Krizaka names and birds are not licensed: [trademark policy](https://github.com/krizaka/.github/blob/main/TRADEMARKS.md).
