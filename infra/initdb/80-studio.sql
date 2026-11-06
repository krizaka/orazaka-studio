-- ============================================================================
-- SOVKORA — Local DB bootstrap · 80 — STUDIO CONTEXT
-- ----------------------------------------------------------------------------
-- Owner: Studio service (sovkora-studio-service :8096 — ADR-034).
-- This file only creates the role and the database of orazaka_studio_db. The schema is
-- Flyway: sovkora-studio-service/src/main/resources/db/migration/V1__studio_baseline.sql,
-- applied at the service's startup as the owner role (krizaka-internal#2255).
--
-- The password is NOT set here. psql 15 cannot read the environment (\getenv is 16+)
-- and ERR-125 bans a shell script, so `sovkora start` applies ALTER ROLE from
-- STUDIO_DB_PASSWORD once the container is healthy. A role created without a
-- password cannot authenticate, so a skipped step fails closed rather than leaving a
-- guessable one (ADR-035, audit #5).
-- ============================================================================

CREATE ROLE orazaka_studio LOGIN;
CREATE DATABASE orazaka_studio_db OWNER orazaka_studio;
