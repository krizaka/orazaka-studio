<!-- krizaka-header -->
<div align="center">

<img src=".github/assets/orazaka-logo.svg" alt="Orazaka" width="420">

# Orazaka Studio

**The AI that never leaves home.**

Studio & pack marketplace: pack catalogue, studio blueprints, installations and saga-driven runs, with its contract (studio-api) and HTTP client (studio-client).

[![CI](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-studio/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f59e0b)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-6366f1)](https://www.krizaka.com/en/products/orazaka)

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

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
