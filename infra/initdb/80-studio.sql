-- ============================================================================
-- ORAZAKA — Local DB bootstrap · 80 — STUDIO CONTEXT
-- ----------------------------------------------------------------------------
-- Owner: Studio service (orazaka-studio-service :8096 — ADR-034).
-- The marketplace of installable business workflows: the catalogue, the
-- versioned declarative blueprints, one actor's installation of a Studio, and
-- the runs those installations produce.
-- A Studio is DATA, not code: publishing one is an admin action, never a
-- deploy — the rule ADR-027/ADR-031 already impose on models, interceptors and
-- pricing.
-- actor_id, pack_key, feature_key, job_id, hold_id and every asset id are
-- OPAQUE values copied in from another context — no inbound or outbound
-- cross-context FK (SEAM-001). Every REFERENCES below targets a table created
-- in THIS file.
-- These tables live in their OWN database (orazaka_studio_db) under the
-- service's own role, created here.
-- ============================================================================

-- The password is NOT set here. psql 15 cannot read the environment (\getenv is 16+)
-- and ERR-125 bans a shell script, so `orazaka start` applies ALTER ROLE from
-- STUDIO_DB_PASSWORD once the container is healthy. A role created without a
-- password cannot authenticate, so a skipped step fails closed rather than leaving a
-- guessable one — which is what the committed literal was (ADR-035, audit #5).
CREATE ROLE orazaka_studio LOGIN;
CREATE DATABASE orazaka_studio_db OWNER orazaka_studio;
\c orazaka_studio_db
SET ROLE orazaka_studio;

-- ── Pack catalogue (PACK_CATALOGUE_ARCHITECTURE §3/§4, ADR-036) ─────────────
-- The marketplace shelf a Pack is browsed under. This lived in billing as
-- `billing_pack.category`, a CHECK constraint defended there on the grounds that
-- "a shelf nobody can name in the front-end is a shelf nobody browses". That
-- reasoning was right and it is exactly why the CHECK is gone: the shelf now HAS a
-- French label, an icon and a sort order, which is what makes it nameable. A closed
-- vocabulary was never the point — an unnameable one was.
-- Still admin-only and still ~5 rows ever: closed in spirit, browsable in fact.
CREATE TABLE pack_category (
    category_key VARCHAR(30) PRIMARY KEY,          -- business | lifestyle
    icon_key     VARCHAR(60) NOT NULL,             -- resolves in the design-system Icon registry
    sort_weight  INT         NOT NULL DEFAULT 0,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE
);

-- Localisation, same mechanism as studio_i18n — ONE i18n shape in this context, which
-- is the whole reason the catalogue moved here rather than growing a second one inside
-- the money service.
CREATE TABLE pack_category_i18n (
    category_key VARCHAR(30) NOT NULL REFERENCES pack_category(category_key) ON DELETE CASCADE,
    locale       VARCHAR(10) NOT NULL,
    label        VARCHAR(80) NOT NULL,
    description  TEXT,
    PRIMARY KEY (category_key, locale)
);

-- What a user buys: identity and content. What it COSTS is billing_pack in
-- 70-billing.sql, in another database. pack_key is the same opaque string on both
-- sides and there is deliberately NO foreign key between them (SEAM-001) — the two
-- halves answer different questions and are deployed by different owners.
-- Splitting them is what stops a marketing copy change from being a deploy of the
-- service that holds the credit ledger.
CREATE TABLE pack (
    pack_key         VARCHAR(50) PRIMARY KEY,      -- OPAQUE, mirrored in billing_pack — no FK
    category_key     VARCHAR(30) NOT NULL REFERENCES pack_category(category_key),
    icon_key         VARCHAR(60) NOT NULL,
    hero_asset_id    VARCHAR(255),                 -- OPAQUE ref into the asset store — no FK
    -- Read since ADR-051: SENSITIVE switches on four controls — data class, shortened
    -- retention, an append-only audit trail and a scope guard. REGULATED adds two more since
    -- ADR-055: versioned consent, blocking at install, and a non-bypassable crisis interceptor.
    regulatory_class VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    -- ADR-061. HOW the pack reaches the user. VERTICAL: a user chooses it and installs it, and the
    -- installation is a row that pins a version and records consent. TOOLKIT: an entitled actor
    -- simply has it; the installation is DERIVED from the entitlement and never stored.
    --
    -- THREE ORTHOGONAL CLASSIFICATIONS, and none is derived from another:
    --   tier             (DATA | CAPABILITY | WORKER)       what the pack contributes — declared in
    --                                                        pack.yaml, not stored on this table
    --   kind             (VERTICAL | TOOLKIT)               how it reaches the user
    --   regulatory_class (STANDARD | SENSITIVE | REGULATED) what controls it forces
    -- A TOOLKIT does NOT imply Tier-C, and it does NOT imply STANDARD: a SENSITIVE toolkit keeps
    -- all four of its run controls, which hang off this row and the run, never the installation.
    -- Nor is kind read from the key: echo-toolkit is a VERTICAL (the Tier-W reference pack).
    -- The single forbidden pairing below is forbidden by WHERE CONSENT IS RECORDED, not by meaning.
    kind             VARCHAR(20) NOT NULL DEFAULT 'VERTICAL',
    -- The domain this pack refuses, DECLARED by the pack (AGENTS.md §12). The engine holds the
    -- mechanism and never the subject: a legal-drafting pack refusing legal advice and a medical
    -- pack refusing diagnosis are one interceptor and two rows. NOT NULL for SENSITIVE — enforced
    -- by the installer, because a control that is optional is not a control.
    scope_guard      JSONB,
    -- REGULATED only (ADR-055): what the user must agree to before install, and what this pack
    -- answers someone in crisis. Both are the pack's declarations; the engine holds neither.
    consent_version  VARCHAR(16),
    consent_statement TEXT,
    -- The same statement in the other languages ({"en": "…"}); consent_statement is the default
    -- language (French). Served by the server in the caller's language (orazaka#301): a legal
    -- notice is never translated by whoever draws the screen.
    consent_statements JSONB,
    safety           JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT | PUBLISHED | WITHDRAWN
    sort_weight      INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pack_regulatory CHECK (regulatory_class IN ('STANDARD','SENSITIVE','REGULATED')),
    CONSTRAINT ck_pack_status     CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    CONSTRAINT ck_pack_kind       CHECK (kind IN ('VERTICAL','TOOLKIT')),
    -- Consent is recorded on studio_installation, and a TOOLKIT has no installation row. A consent
    -- gate with nowhere to record what was agreed to is not a consent gate. In SQL rather than in a
    -- service: a control that exists only in Java is one INSERT away from not existing.
    CONSTRAINT ck_pack_toolkit_not_regulated
        CHECK (kind <> 'TOOLKIT' OR regulatory_class <> 'REGULATED'),
    -- The same reason one step down. Below REGULATED both declarations are optional, and both are
    -- still answered from an installation row: consent is recorded on it, and the crisis reply is
    -- chosen by its region. A SENSITIVE toolkit declaring safety would answer a crisis with nothing.
    CONSTRAINT ck_pack_toolkit_no_installation_controls
        CHECK (kind <> 'TOOLKIT' OR (consent_version IS NULL AND safety IS NULL))
);
-- The browse query: one shelf, heaviest first, published only. Mirrors the index the
-- billing table used to carry, now over the columns that actually drive the page.
CREATE INDEX idx_pack_browse ON pack(category_key, sort_weight DESC) WHERE status = 'PUBLISHED';

CREATE TABLE pack_i18n (
    pack_key    VARCHAR(50)  NOT NULL REFERENCES pack(pack_key) ON DELETE CASCADE,
    locale      VARCHAR(10)  NOT NULL,
    label       VARCHAR(120) NOT NULL,
    tagline     VARCHAR(255),
    description TEXT,
    PRIMARY KEY (pack_key, locale)
);

-- ── Catalogue (design §5) ───────────────────────────────────────────────────
-- The marketplace item. A new profession is a ROW here plus a blueprint row —
-- never a Java class, which is the whole point of ADR-034.
CREATE TABLE studio (
    studio_key       VARCHAR(60)  PRIMARY KEY,       -- stable, kebab-case: realestate-reels
    label            VARCHAR(120) NOT NULL,
    tagline          VARCHAR(255),
    profession       VARCHAR(60)  NOT NULL,          -- real-estate | trades | sales | …
    icon_key         VARCHAR(60)  NOT NULL,          -- resolves in the design-system Icon registry
    hero_asset_id    VARCHAR(255),                   -- OPAQUE ref into the asset store — no FK
    pricing          VARCHAR(20)  NOT NULL,          -- FREE | INCLUDED | PAID
    pack_key      VARCHAR(50),                    -- OPAQUE ref into billing — no FK
    entitlement_key  VARCHAR(120) NOT NULL,          -- studio.<studio_key>
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    publisher_id     VARCHAR(255) NOT NULL DEFAULT 'orazaka',
    latest_version   VARCHAR(20),                    -- semver of the newest PUBLISHED blueprint
    sort_weight      INT          NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A PAID Studio with no package is unsellable AND unreachable: the install
    -- path has nothing to open a checkout for. Mirrored in Studio's compact
    -- constructor — the invariant belongs to the type as well as to the table.
    CONSTRAINT studio_pricing_needs_package
        CHECK (pricing <> 'PAID' OR pack_key IS NOT NULL)
);
CREATE INDEX idx_studio_browse ON studio(status, profession, sort_weight DESC);

-- Localisation. The UI falls back to the base studio row when a locale is absent.
CREATE TABLE studio_i18n (
    studio_key  VARCHAR(60) NOT NULL REFERENCES studio(studio_key) ON DELETE CASCADE,
    locale      VARCHAR(10) NOT NULL,
    label       VARCHAR(120) NOT NULL,
    tagline     VARCHAR(255),
    description TEXT,
    PRIMARY KEY (studio_key, locale)
);

-- The bundle: a Pack is a set of Studios, never one Studio (design §1).
-- It sits HERE rather than beside the other pack tables above because it references
-- studio(studio_key), and Postgres needs that table to exist first — a Pack is browsed
-- before a Studio is installed, but it is DECLARED after one.
-- Adding a Studio to a Pack is one row here plus one billing_pack_entitlement row in
-- 70-billing.sql; PackCoherenceRules fails the build when the second is forgotten,
-- because that omission is silent, looks like a billing bug, and locks out the exact
-- customer who just paid (ADR-036, invariant #3).
CREATE TABLE pack_studio (
    pack_key    VARCHAR(50) NOT NULL REFERENCES pack(pack_key) ON DELETE CASCADE,
    studio_key  VARCHAR(60) NOT NULL REFERENCES studio(studio_key) ON DELETE CASCADE,
    sort_weight INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (pack_key, studio_key)
);
-- "Which Packs grant this Studio?" — the query behind the lock notice's upsell, and the
-- reverse of the primary key, so it needs its own index.
CREATE INDEX idx_pack_studio_studio ON pack_studio(studio_key);

-- One immutable, semver'd version of a Studio's DAG.
CREATE TABLE studio_blueprint (
    studio_key        VARCHAR(60) NOT NULL REFERENCES studio(studio_key) ON DELETE CASCADE,
    version           VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | PUBLISHED | DEPRECATED
    definition        JSONB       NOT NULL,                  -- steps + outputs (design §6)
    input_schema      JSONB       NOT NULL,                  -- JSON Schema 2020-12 → the run form
    config_schema     JSONB       NOT NULL DEFAULT '{}',     -- asked ONCE at install
    estimated_credits BIGINT      NOT NULL DEFAULT 0,        -- the hold amount for one run
    changelog         TEXT,
    published_at      TIMESTAMPTZ,
    created_by        VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (studio_key, version)
);

-- A PUBLISHED blueprint is immutable: editing means minting a new version, because
-- an installation pins a version and a changed prompt is a changed product.
-- Same construction as credit_ledger_entry (ADR-033) — a REVOKE would not hold,
-- the table owner keeps implicit rights on its own table. Unlike the ledger's
-- blanket ban this trigger is CONDITIONAL: a DRAFT → PUBLISHED → DEPRECATED status
-- transition must still be possible.
CREATE OR REPLACE FUNCTION studio_blueprint_immutable() RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'studio_blueprint is append-only once published';
  END IF;
  IF OLD.status = 'PUBLISHED' AND (NEW.definition IS DISTINCT FROM OLD.definition
        OR NEW.input_schema IS DISTINCT FROM OLD.input_schema) THEN
    RAISE EXCEPTION 'published blueprint % %: mint a new version', OLD.studio_key, OLD.version;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_studio_blueprint_immutable
  BEFORE UPDATE OR DELETE ON studio_blueprint
  FOR EACH ROW EXECUTE FUNCTION studio_blueprint_immutable();

-- ── Workspace (design §5) ───────────────────────────────────────────────────
-- One actor's copy of a Studio: a pinned version plus their own configuration.
CREATE TABLE studio_installation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       VARCHAR(255) NOT NULL,             -- OPAQUE — no FK into identity
    studio_key     VARCHAR(60)  NOT NULL REFERENCES studio(studio_key),
    pinned_version VARCHAR(20)  NOT NULL,             -- upgrades are explicit, never silent
    status         VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    config         JSONB        NOT NULL DEFAULT '{}',
    -- ADR-055. Consent is to a STATEMENT: the version is recorded so a changed statement asks
    -- again. Age is ATTESTED, because identity holds no date of birth and collecting one from
    -- every user for one pack is data minimisation backwards. Region decides which verified
    -- crisis line answers, and has no default: unstated is an install that does not happen.
    consent_version     VARCHAR(16),
    consent_recorded_at TIMESTAMPTZ,
    age_attested_at     TIMESTAMPTZ,
    region              VARCHAR(8),
    CONSTRAINT ck_installation_consent
        CHECK ((consent_version IS NULL) = (consent_recorded_at IS NULL)),
    installed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_run_at    TIMESTAMPTZ,
    UNIQUE (actor_id, studio_key)                     -- one installation per actor per studio
);
CREATE INDEX idx_installation_actor ON studio_installation(actor_id, status);
CREATE INDEX idx_installation_consent ON studio_installation(studio_key, consent_version) WHERE consent_version IS NOT NULL;

-- ── Operator grants ─────────────────────────────────────────────────────────
-- A Studio entitlement given to an actor by the OPERATOR of this platform, outside any plan: a
-- first-party product calling Orazaka with its service account (Yomizaka runs the document
-- Studios), a pilot customer, an environment where nothing is sold (dev, BILLING_ENABLED=false).
-- It only ever ADDS to billing's answer (StudioAccessService): no row, billing decides alone.
-- Data, never configuration: granted and revoked here, each with its reason and, when it is a
-- pilot, its end. entitlement_key is the studio's (studio.<studioKey>) — no FK, a grant may name
-- a Studio its pack has not installed yet. actor_id is OPAQUE (SEAM-001).
CREATE TABLE studio_entitlement_grant (
    actor_id        VARCHAR(255) NOT NULL,
    entitlement_key VARCHAR(120) NOT NULL,
    reason          TEXT         NOT NULL CHECK (length(trim(reason)) > 0),
    granted_by      VARCHAR(255) NOT NULL,
    granted_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    PRIMARY KEY (actor_id, entitlement_key)
);

-- ── Execution (design §5) ───────────────────────────────────────────────────
-- blueprint_version is DENORMALISED on purpose: a run stays reproducible after
-- the installation is upgraded to a newer blueprint.
CREATE TABLE studio_run (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL for a TOOLKIT run (ADR-061): its installation is derived from entitlement and has no row
    -- to point at. The run is unaffected — its version is blueprint_version below, resolved ONCE at
    -- start and never again, and every other reader of the installation degrades to "no row".
    installation_id   UUID REFERENCES studio_installation(id) ON DELETE CASCADE,
    actor_id          VARCHAR(255) NOT NULL,          -- OPAQUE — no FK into identity
    studio_key        VARCHAR(60)  NOT NULL,
    blueprint_version VARCHAR(20)  NOT NULL,
    status            VARCHAR(30)  NOT NULL,
    hold_id           VARCHAR(255),                   -- OPAQUE ref into billing — no FK
    correlation_id    VARCHAR(255) NOT NULL,
    inputs            JSONB        NOT NULL DEFAULT '{}',
    outputs           JSONB,
    error_message     TEXT,
    -- Stamped from the pack's regulatory_class when the run is created, never derived later
    -- (ADR-051). A run carries its own class because that is what survives the pack being
    -- withdrawn, re-classified or uninstalled — the obligation attaches to the data, not to a
    -- row somebody can still edit.
    data_class        VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    -- ADR-053. Nullable and unconstrained: the vocabulary is a Java enum, and a CHECK listing
    -- its members here would be the same closed set written twice. NULL reads as EXECUTOR_FAULT
    -- — release the hold, blame nobody.
    failure_cause     VARCHAR(32),
    -- ADR-083. The caller's Idempotency-Key and the fingerprint of the request that carried it:
    -- the same key + the same request answers this run instead of starting another. Nullable (most
    -- callers send none); the key lives and dies with the run, so retention needs no second sweep.
    idempotency_key     VARCHAR(255),
    request_fingerprint VARCHAR(64),
    -- orazaka-studio#61. The fingerprint of what was asked (inputs and the installation's configuration, for this
    -- Studio version), on every run; and, on a run answered from the exact result cache, the run whose result it
    -- copies. A cached run is never a source: every answer names the run that really computed it.
    input_fingerprint VARCHAR(64),
    cached_from       UUID,
    -- orazaka-studio#62. A run a STUDIO step started: it holds nothing (its work is settled inside the parent's
    -- hold) and its outcome becomes the parent step's, found by the step's job_id = this run's id. One level.
    parent_run_id     UUID REFERENCES studio_run(id) ON DELETE CASCADE,
    -- orazaka-studio#63. The run this one resumes: its steps before the resumed one were copied with their outputs
    -- (and no consumption, so they are never billed twice). No FK: the origin may be purged by retention first.
    resumed_from      UUID,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    CONSTRAINT ck_run_data_class CHECK (data_class IN ('STANDARD','SENSITIVE','REGULATED')),
    -- ck_pack_toolkit_not_regulated, restated where it can be checked without a join: a run with no
    -- installation cannot carry REGULATED data, because the consent that data needs lives on the
    -- installation. Without this, a nullable installation_id would be a consent bypass one INSERT
    -- wide — the pack CHECK guards the catalogue; this one guards the run table.
    CONSTRAINT ck_run_uninstalled_not_regulated
        CHECK (installation_id IS NOT NULL OR data_class <> 'REGULATED')
);
-- The exact result cache's lookup: one actor, one Studio version, one request, an original success.
CREATE INDEX idx_studio_run_result_cache ON studio_run (actor_id, studio_key, blueprint_version, input_fingerprint)
    WHERE status = 'SUCCEEDED' AND cached_from IS NULL;
-- The sweeper's second window and the analytics exclusion both filter on this.
CREATE INDEX idx_studio_run_parent ON studio_run(parent_run_id) WHERE parent_run_id IS NOT NULL;
CREATE INDEX idx_run_data_class ON studio_run(data_class, finished_at) WHERE data_class <> 'STANDARD';
CREATE INDEX idx_studio_run_failure_cause ON studio_run(failure_cause) WHERE failure_cause IS NOT NULL;
-- The backstop of the advisory lock (ADR-083): one run per (actor, key), whatever the code does.
CREATE UNIQUE INDEX ux_studio_run_idempotency ON studio_run(actor_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;


-- ─────────────────────────────────────────────────────────────────────────────
-- The audit trail a SENSITIVE run leaves behind (ADR-051).
--
-- Same construction as credit_ledger_entry, trigger included, and for the same reason: an audit
-- row somebody can edit is not an audit row. A BEFORE UPDATE OR DELETE trigger rather than a
-- REVOKE, because the table owner keeps implicit rights on its own table and a grant-based rule
-- is therefore not a constraint.
--
-- It records WHAT HAPPENED, never what was said: run, actor, event, and the step the event
-- concerns. Putting the user's material in the audit trail would defeat the retention control
-- sitting next to it — the sweeper deletes the run and the audit row would keep a copy.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE studio_run_audit (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      UUID         NOT NULL,          -- no FK: the run is deleted by retention, the trail is not
    actor_id    VARCHAR(255) NOT NULL,          -- OPAQUE
    pack_key    VARCHAR(50)  NOT NULL,
    studio_key  VARCHAR(60)  NOT NULL,
    data_class  VARCHAR(20)  NOT NULL,
    event       VARCHAR(40)  NOT NULL,          -- RUN_STARTED | RUN_SUCCEEDED | RUN_FAILED | SCOPE_REFUSED | RUN_ERASED | DATA_ERASED
    step_id     VARCHAR(60),
    -- No `detail`. It was "a reason code, never user content" and carried the failed step's message
    -- — for a refusal, the pack's reviewed answer, a crisis response on a REGULATED pack. The trail
    -- records that a run happened and how it ended, never what it matched (ADR-065).
    -- The category, not the sentence: a SENSITIVE pack must be able to show that a
    -- refused run was PROTECTED and not BROKEN (ADR-053 §1).
    failure_cause VARCHAR(32),
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_data_class CHECK (data_class IN ('SENSITIVE','REGULATED'))
);
CREATE INDEX idx_run_audit_run   ON studio_run_audit(run_id, recorded_at);
CREATE INDEX idx_run_audit_actor ON studio_run_audit(actor_id, recorded_at DESC);
-- "How many protected runs did we REFUSE, and how many did we BREAK?" is one query (ADR-053 §1).
CREATE INDEX idx_run_audit_cause ON studio_run_audit(failure_cause) WHERE failure_cause IS NOT NULL;

CREATE OR REPLACE FUNCTION studio_run_audit_immutable() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'studio_run_audit is append-only (ADR-051) — % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_studio_run_audit_immutable
  BEFORE UPDATE OR DELETE ON studio_run_audit
  FOR EACH ROW EXECUTE FUNCTION studio_run_audit_immutable();
CREATE INDEX idx_run_actor_time ON studio_run(actor_id, started_at DESC);
-- The sweeper's index: a run that never reached a terminal state is the failure mode
-- that silently freezes a paying actor's credits (ADR-034 "the failure mode to guard").
CREATE INDEX idx_run_active ON studio_run(status) WHERE finished_at IS NULL;

-- (run_id, step_id, ordinal) is half the idempotency story: at-least-once delivery of
-- job.{id}.done must not double-submit a video job. `ordinal` distinguishes fan-out
-- instances of the same step.
CREATE TABLE studio_run_step (
    run_id        UUID NOT NULL REFERENCES studio_run(id) ON DELETE CASCADE,
    step_id       VARCHAR(60) NOT NULL,
    ordinal       INT NOT NULL DEFAULT 0,             -- fan-out index
    job_id        VARCHAR(36),                        -- OPAQUE ref into orazaka_jobs — no FK
    status        VARCHAR(30) NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    output        JSONB,
    error_message TEXT,
    failure_cause VARCHAR(32),   -- declared by the executor (ADR-053)
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    -- WHICH LANE this step was dispatched into, stamped at dispatch from the capability's row
    -- (ADR-067). The sweeper's ceiling is per lane and `started_at` is stamped when this row is
    -- INSERTED — at dispatch, before any worker takes the message — so queue wait counts against
    -- the deadline. One global ceiling therefore had to be large enough not to reap a 67-second
    -- image that queued and small enough to catch a 200 ms text step that hung, which is not one
    -- number. Read here rather than re-resolved: the class that applied is the one the platform
    -- held when the work was sent, and a capability reclassified afterwards must not change the
    -- deadline of a step already in flight.
    latency_class VARCHAR(20),
    -- What this step consumed, as the executor measured it, and what ran (ADR-041). Kept per step
    -- because the run's hold is settled ONCE at the sum, and the sum has to survive a restart
    -- between step four finishing and step five: an accumulator in memory would lose it, and a
    -- run whose measurements are lost settles at nothing.
    --
    -- Raw measurements, never credits: which unit this prices in belongs to the pricebook row
    -- (billable_capability, model_name), and that table is in another database owned by another
    -- service. Storing a credit figure here would be this context pricing work it does not own.
    consumption   JSONB,
    model_name    VARCHAR(120),
    -- A SESSION step (ADR-093, orazaka#450) stays RUNNING while a client sends turns: this is the last one, and the idle
    -- TTL (session.idle-ttl-seconds) runs from it. NULL until the first turn (the TTL then runs from started_at).
    last_turn_at  TIMESTAMPTZ,
    PRIMARY KEY (run_id, step_id, ordinal)
);
CREATE INDEX idx_run_step_job ON studio_run_step(job_id) WHERE job_id IS NOT NULL;

-- ── Plumbing (same shape as every other context) ────────────────────────────
-- Studio transactional outbox (AGENTS.md §6) — evt.studio.* is never published
-- directly inside a transaction.
CREATE TABLE studio_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(120) NOT NULL,   -- the routing key: evt.studio.run.* or a capability's job.*
    payload         JSONB NOT NULL,
    -- WHICH exchange, because this table now carries COMMANDS as well as events (ADR-067). A step
    -- dispatch used to be published straight from RunSagaService.advance while that method was
    -- @Transactional: a rollback after the send left a job running against a step row that no
    -- longer existed, holding credits in another service that the rollback could not reach.
    exchange        VARCHAR(100) NOT NULL DEFAULT 'orazaka.events',
    -- The AMQP messageId the consumer dedups on. For a step dispatch it is the job id, which is
    -- how a redelivered dispatch is skipped rather than run twice; NULL for an event, whose
    -- consumers key on their own ids.
    message_id      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts        INT NOT NULL DEFAULT 0,
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_studio_outbox_pending ON studio_outbox(next_attempt_at) WHERE published_at IS NULL;

-- Consumer-side idempotency (AGENTS.md §6) — this service's OWN copy of the dedup
-- ledger, in its own database (contract-copy doctrine, no shared table). The key is
-- (consumer, message_id) like every other context: the saga listener and the
-- subscription listener consume different messages, and a single-column key would
-- let whichever consumer ran first silently swallow the other's delivery.
CREATE TABLE processed_messages (
    consumer     VARCHAR(100) NOT NULL,
    message_id   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- Post-startup behaviour an admin flips live — this service's own copy of the
-- runtime-config shape. Limits are ROWS, never yaml (AGENTS.md §4, ADR-027/031).
CREATE TABLE studio_runtime_config (
    config_key   VARCHAR(120) PRIMARY KEY,
    config_value TEXT NOT NULL,
    value_type   VARCHAR(20) NOT NULL,
    description  TEXT NOT NULL,
    default_value TEXT NOT NULL,
    min_value    BIGINT,
    max_value    BIGINT,
    max_items    INTEGER,
    CONSTRAINT ck_studio_runtime_config_bounds CHECK (
        (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
        AND (max_items IS NULL OR max_items >= 0)
    )
);

CREATE TABLE studio_runtime_config_audit (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id VARCHAR(255) NOT NULL,
    config_key VARCHAR(120) NOT NULL,
    before_value TEXT NOT NULL,
    after_value TEXT NOT NULL
);
CREATE INDEX idx_studio_runtime_config_audit_at
    ON studio_runtime_config_audit(at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- CONTENT POLICY — a policy engine the OPERATOR configures, not a built-in filter.
-- Default (no rows): permissive. Classification still runs and is audited, nothing is refused —
-- except minors_risk, which is refused always and is not a setting (illegal everywhere).
-- ─────────────────────────────────────────────────────────────────────────────
-- Per tenant: what to do with a label. subject = '*' (install-wide) or one actor id.
CREATE TABLE content_policy_tenant (
    subject  VARCHAR(255) NOT NULL,
    label    VARCHAR(30)  NOT NULL,
    action   VARCHAR(10)  NOT NULL,
    PRIMARY KEY (subject, label),
    CONSTRAINT ck_cpt_label  CHECK (label IN ('SEXUAL_EXPLICIT','SEXUAL_SUGGESTIVE','VIOLENCE_GRAPHIC','REAL_PERSON_LIKENESS')),
    CONSTRAINT ck_cpt_action CHECK (action IN ('ALLOW','REFUSE'))
);
-- Per provider (a capability's feature key): what its TERMS allow. Absent row = no restriction.
-- BLOCK refuses the run before the provider is reached; WARN lets it through and logs.
CREATE TABLE content_provider_policy (
    provider_key                VARCHAR(255) PRIMARY KEY,
    allows_adult_content        BOOLEAN     NOT NULL DEFAULT TRUE,
    allows_graphic_violence     BOOLEAN     NOT NULL DEFAULT TRUE,
    allows_real_person_likeness BOOLEAN     NOT NULL DEFAULT TRUE,
    enforcement                 VARCHAR(5)  NOT NULL DEFAULT 'BLOCK',
    note                        TEXT,
    CONSTRAINT ck_cpp_enforcement CHECK (enforcement IN ('BLOCK','WARN'))
);
-- Extra classifier patterns the operator adds (regex over lower-cased, accent-folded text).
CREATE TABLE content_policy_rule (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label   VARCHAR(30) NOT NULL,
    pattern TEXT        NOT NULL,
    CONSTRAINT ck_cpr_label CHECK (label IN ('SEXUAL_EXPLICIT','SEXUAL_SUGGESTIVE','VIOLENCE_GRAPHIC','REAL_PERSON_LIKENESS'))
);
-- Append-only: the label and the rule, NEVER the prompt (ADR-065).
CREATE TABLE content_policy_audit (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    VARCHAR(255) NOT NULL,
    studio_key  VARCHAR(60)  NOT NULL,
    labels      VARCHAR(200) NOT NULL,
    decision    VARCHAR(10)  NOT NULL,          -- ALLOWED | WARNED | REFUSED
    rule        VARCHAR(300) NOT NULL,
    providers   TEXT         NOT NULL DEFAULT '',
    dry_run     BOOLEAN      NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_cpa_actor ON content_policy_audit(actor_id, recorded_at DESC);
CREATE INDEX idx_cpa_decision ON content_policy_audit(decision, recorded_at DESC);
CREATE OR REPLACE FUNCTION content_policy_audit_immutable() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'content_policy_audit is append-only — % rejected', TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_content_policy_audit_immutable
  BEFORE UPDATE OR DELETE ON content_policy_audit
  FOR EACH ROW EXECUTE FUNCTION content_policy_audit_immutable();

-- ============================================================================
-- STUDIO SEED DATA (dev only, idempotent)
-- ============================================================================

-- An admin's active value survives startup; seed defaults and bounds stay aligned with the schema.
INSERT INTO studio_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items)
VALUES
 ('run.max-concurrent-per-actor', '2', 'int', 'Concurrent runs one actor may hold.', '2', 1, 100, NULL),
 ('run.result-cache.studios', 'compliance-check', 'list', 'Studios whose identical runs (same actor, version, inputs, configuration) answer from the recorded result, with no hold and no model call (orazaka-studio#61). Only a Studio whose result is a function of its inputs and version: a deterministic one, or compliance-check, whose ruleset is pinned by (kind, asOf) and whose judgment is recorded (ADR-089 §4). Empty = off.', 'compliance-check', NULL, NULL, 100),
 ('run.max-seconds', '14400', 'int', 'Longest a run may stay in flight (RUNNING) before the sweeper fails it and frees its hold and its slot.', '14400', 60, 86400, NULL),
 ('run.concurrency-retry-seconds', '15', 'int', 'Retry-After (seconds) of the 429 answered when an actor holds its concurrent runs.', '15', 1, 300, NULL),
 ('run.max-streams-per-actor', '5', 'int', 'Run event streams (SSE) one actor may hold open per instance.', '5', 1, 100, NULL),
 ('run.events.poll-millis', '1500', 'int', 'How often an open run event stream reads the run.', '1500', 100, 60000, NULL),
 ('run.events.heartbeat-seconds', '15', 'int', 'Silence after which a run event stream sends a keep-alive comment.', '15', 1, 300, NULL),
 ('run.events.max-seconds', '1800', 'int', 'Longest a run event stream stays open; the client reconnects.', '1800', 60, 86400, NULL),
    -- One ceiling per LANE (ADR-067). A single number served neither: 900 s was 9x the p95 of an
    -- image generation (100.5 s measured) and 120x the p95 of a chat step (7.4 s), so a hung
    -- interactive step sat for a quarter of an hour while a legitimately queued image was still at
    -- risk once the queue genuinely fills.
    ('run.step-timeout-seconds.interactive', '300', 'int', 'Per-step ceiling in the INTERACTIVE lane — 30x the measured p95 of a chat or analysis step.', '300', 1, 3600, NULL),
    ('run.step-timeout-seconds.batch', '1800', 'int', 'Per-step ceiling in the BATCH lane — room for queue wait behind ~25 image generations at the measured p50 of 67 s.', '1800', 1, 14400, NULL),
 ('run.fan-out-max', '10', 'int', 'Hard cap on forEach expansion — protects the MLX budget.', '10', 1, 1000, NULL),
 ('run.blueprint-cache-size', '100', 'int', 'Maximum published blueprint versions parsed and cached per service instance; 0 disables the cache.', '100', 0, 10000, NULL),
 ('run.outcome-listener.concurrency', '2', 'int', 'Outcome listener consumer count per service instance, adjusted live on its virtual-thread executor.', '2', 1, 64, NULL),
 ('marketplace.third-party-enabled', 'false', 'boolean', 'Opens publishing to non-orazaka publishers.', 'false', NULL, NULL, NULL),
 ('retention.run-days', '90', 'int', 'Days a finished run and its artefacts are kept (GDPR / Loi 25).', '90', 1, 3650, NULL),
 -- SENSITIVE runs age out sooner, and an installation may lower this further but never raise it:
 -- the sweeper takes the minimum of the two, so a pack cannot buy itself a longer memory (ADR-051).
 ('retention.sensitive-run-days', '30', 'int', 'Retention window for runs of a SENSITIVE pack. An installation may lower it, never raise it.', '30', 1, 3650, NULL),
 ('retention.revoked-installation-days', '30', 'int', 'Grace period before an uninstalled Studio loses its saved configuration.', '30', 0, 3650, NULL)
ON CONFLICT (config_key) DO UPDATE SET
    default_value = EXCLUDED.default_value,
    min_value = EXCLUDED.min_value,
    max_value = EXCLUDED.max_value,
    max_items = EXCLUDED.max_items;

-- ── Catalogue content: none. It ships as bundles (ADR-037 phase D) ──────────
--
-- This file used to seed three Studios, one pack, two shelves, their translations and three
-- blueprints — 288 lines of product content in the file that creates the schema. They now live
-- in orazaka-packs/{realestate-studio,trade-showcase,outbound-prospection} and are applied by
-- `orazaka pack install`, which writes exactly these tables through PackInstallerService.
--
-- The move is a transcription: every value in those bundles came out of the rows deleted here,
-- and `orazaka pack install` writes them back identically. What changed is WHERE the content
-- lives, never what it says.
--
-- Why the schema file must not hold the content. A seed is applied once, by Postgres, at
-- container creation, from inside this repository. That is three assumptions a pack cannot
-- make: a pack ships outside this repo (phase G), it is installed against a running platform
-- rather than an empty one, and it is installed more than once — upgraded, re-applied,
-- uninstalled. Content that lives in initdb is content that can only ever be ours, and phases
-- E, H and J exist to ship content that is not.
--
-- Consequence for a fresh database: `orazaka start` now yields an EMPTY catalogue. The Studios
-- appear once the services are up and `orazaka pack install --all ./orazaka-packs` has run —
-- which is also the precondition of the Studio end-to-end suite, since it asks for
-- trade-showcase and realestate-reels by name. That extra step is not an oversight: a Studio is
-- now INSTALLED rather than assumed, and a bootstrap that hid the difference would mean the
-- install path is only ever exercised by the packs we happen to ship.
--
-- studio_runtime_config above stays: it is infrastructure wiring for the interpreter (timeouts,
-- fan-out caps, retention), not catalogue content, and it must be there before any pack is.
-- ─────────────────────────────────────────────────────────────────────────────
-- SIGNED WEBHOOKS (orazaka#189 slice B, ADR-083): a client that cannot hold a stream open is told when a run is over.
-- An endpoint belongs to one actor (the API key's owner); `secret` signs the body (Standard Webhooks, HMAC-SHA256) and
-- `previous_secret` stays valid until `previous_secret_until`, so a receiver can rotate without losing a delivery.
-- A delivery is one (endpoint, run, event): at-least-once, retried with exponential backoff, then DEAD — the log of
-- what was tried is the table itself. Idempotent: safe to re-apply.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS studio_webhook_endpoint (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id              VARCHAR(255) NOT NULL,          -- OPAQUE — no FK into identity
    url                   TEXT         NOT NULL,
    secret                TEXT         NOT NULL,          -- SEALED: v1.<key>.<wrapped>.<nonce>.<ciphertext> (master keyring, ADR-054)
    previous_secret       TEXT,                           -- sealed too
    previous_secret_until TIMESTAMPTZ,
    events                TEXT         NOT NULL DEFAULT 'run.completed,run.failed',
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_studio_webhook_endpoint_actor ON studio_webhook_endpoint (actor_id) WHERE enabled;

CREATE TABLE IF NOT EXISTS studio_webhook_delivery (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID         NOT NULL REFERENCES studio_webhook_endpoint(id) ON DELETE CASCADE,
    run_id          UUID         NOT NULL,
    event           VARCHAR(40)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_http_status INT,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING','DELIVERED','DEAD')),
    CONSTRAINT ux_webhook_delivery UNIQUE (endpoint_id, run_id, event)
);
CREATE INDEX IF NOT EXISTS idx_studio_webhook_delivery_due ON studio_webhook_delivery (next_attempt_at) WHERE status = 'PENDING';

INSERT INTO studio_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items)
VALUES
 ('webhook.max-attempts', '8', 'int', 'Deliveries tried before a webhook is DEAD.', '8', 1, 100, NULL),
 ('webhook.backoff-seconds', '30', 'int', 'Wait after the first failed delivery; it doubles at each attempt.', '30', 1, 86400, NULL),
 ('webhook.backoff-cap-seconds', '3600', 'int', 'Longest wait between two attempts.', '3600', 1, 604800, NULL),
 ('webhook.timeout-seconds', '10', 'int', 'How long a receiver has to answer.', '10', 1, 300, NULL),
 ('webhook.batch-size', '20', 'int', 'Deliveries taken per sweep and instance.', '20', 1, 1000, NULL),
 ('webhook.max-endpoints-per-actor', '5', 'int', 'Webhook endpoints one actor may hold.', '5', 1, 100, NULL),
 ('webhook.rotation-grace-hours', '24', 'int', 'How long the previous secret keeps signing next to the new one.', '24', 0, 720, NULL)
ON CONFLICT (config_key) DO UPDATE SET
    default_value = EXCLUDED.default_value,
    min_value = EXCLUDED.min_value,
    max_value = EXCLUDED.max_value,
    max_items = EXCLUDED.max_items;

INSERT INTO studio_runtime_config
    (config_key, config_value, value_type, description, default_value, min_value, max_value, max_items)
VALUES
 ('session.idle-ttl-seconds', '1800', 'int', 'Silence after which a SESSION step (the code agent''s run) is closed as expired: no turn, no finish. The run then settles and takes no more turns.', '1800', 60, 86400, NULL)
ON CONFLICT (config_key) DO UPDATE SET
    default_value = EXCLUDED.default_value,
    min_value = EXCLUDED.min_value,
    max_value = EXCLUDED.max_value,
    max_items = EXCLUDED.max_items;

-- RUN EVENTS (orazaka#241, ADR-083 slice 10): a run's changes are numbered, in the database, so every instance and
-- every reconnecting client agrees on the numbers — `GET /runs/{id}/events` sends the number as the SSE `id`, and a
-- client that comes back with `Last-Event-ID` is sent only what it has not seen.
-- `studio_run.event_seq` rises by one at each change of the run (status, outputs, error, end) or of one of its steps
-- (inserted, status or output changed); the update that raises it holds the run's row lock, so the numbers are gapless
-- per run whoever writes. `studio_run_event` keeps what each number was (kind + a few words), and goes with its run.
-- A trigger, not application code: the saga, the sweeper and the settlement all write these tables. Idempotent.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE studio_run ADD COLUMN IF NOT EXISTS event_seq BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS studio_run_event (
    run_id     UUID         NOT NULL REFERENCES studio_run(id) ON DELETE CASCADE,
    seq        BIGINT       NOT NULL,
    kind       VARCHAR(30)  NOT NULL,
    detail     JSONB        NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

CREATE OR REPLACE FUNCTION studio_run_event_number() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.event_seq := 1;
    ELSIF NEW.event_seq = OLD.event_seq AND (
            NEW.status IS DISTINCT FROM OLD.status OR NEW.outputs IS DISTINCT FROM OLD.outputs
            OR NEW.error_message IS DISTINCT FROM OLD.error_message
            OR NEW.finished_at IS DISTINCT FROM OLD.finished_at) THEN
        NEW.event_seq := OLD.event_seq + 1;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION studio_run_event_record() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.event_seq IS DISTINCT FROM OLD.event_seq THEN
        INSERT INTO studio_run_event (run_id, seq, kind, detail)
        VALUES (NEW.id, NEW.event_seq, CASE WHEN TG_OP = 'INSERT' THEN 'run.created' ELSE 'run.updated' END,
                jsonb_build_object('status', NEW.status))
        ON CONFLICT (run_id, seq) DO NOTHING;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION studio_run_event_step() RETURNS trigger AS $$
BEGIN
    UPDATE studio_run SET event_seq = event_seq + 1 WHERE id = NEW.run_id;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_studio_run_event_number ON studio_run;
CREATE TRIGGER trg_studio_run_event_number BEFORE INSERT OR UPDATE ON studio_run
    FOR EACH ROW EXECUTE FUNCTION studio_run_event_number();
DROP TRIGGER IF EXISTS trg_studio_run_event_record ON studio_run;
CREATE TRIGGER trg_studio_run_event_record AFTER INSERT OR UPDATE ON studio_run
    FOR EACH ROW EXECUTE FUNCTION studio_run_event_record();
DROP TRIGGER IF EXISTS trg_studio_run_event_step_new ON studio_run_step;
CREATE TRIGGER trg_studio_run_event_step_new AFTER INSERT ON studio_run_step
    FOR EACH ROW EXECUTE FUNCTION studio_run_event_step();
DROP TRIGGER IF EXISTS trg_studio_run_event_step_changed ON studio_run_step;
CREATE TRIGGER trg_studio_run_event_step_changed AFTER UPDATE ON studio_run_step
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status OR NEW.output IS DISTINCT FROM OLD.output)
    EXECUTE FUNCTION studio_run_event_step();

-- ADR-085: every operator write of the Studio service (blueprints, pack bundles): who/what/target/request/outcome (ids only).
CREATE TABLE IF NOT EXISTS operator_audit (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id VARCHAR(255),
    actor_label VARCHAR(255) NOT NULL,
    action VARCHAR(160) NOT NULL,
    target_id VARCHAR(255),
    request_id VARCHAR(64),
    outcome VARCHAR(12) NOT NULL DEFAULT 'ok'
);
CREATE INDEX IF NOT EXISTS idx_operator_audit_at ON operator_audit(at DESC);
