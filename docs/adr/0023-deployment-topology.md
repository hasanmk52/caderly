# ADR 0023 — Deployment topology: host networking, loopback-only app, superuser backups

**Status:** Accepted
**Date:** 2026-11-07
**Deciders:** Hasan (solo dev)
**Relates to:** PRD §19.8, §27, CLAUDE.md §3/§5/§6 A05, ADR 0019 decision A, ADR 0022

---

## Context

Phase 1.16 put Caderly on a real server. The app trusts the first `X-Forwarded-For` hop as the
client address (Super Admin IP allowlist, login rate limits), so it must be reachable only through
the proxy. PostgreSQL is native on the host and every tenant table forces row-level security.

## Decision

1. **Docker host networking** for the app and the proxy. The app reaches Postgres on `localhost`,
   so Postgres keeps its default loopback-only listener and no `pg_hba.conf` or restart is needed.
   The alternative (a bridge network plus opening Postgres to it) widens the database's exposure and
   was rejected. Cost: Linux only; local development does not use the compose file.
2. **The app listens on `127.0.0.1`.** Only the proxy on the same machine can reach it, so a client
   cannot send a forged `X-Forwarded-For`. The proxy sets that header from the real peer address.
3. **Caddy issues one wildcard certificate** through the DNS-01 challenge, using a zone-scoped DNS
   API token. DNS records for the apex and the wildcard are DNS-only (not proxied by the DNS
   provider), so the app still sees the real client address and the certificate stays ours.
4. **Actuator on a separate loopback-only management port** in deployment, which the proxy never
   forwards. Security rules apply on every port, so `/actuator/prometheus` is explicitly permitted
   only when the request arrives on that port (`SecurityConfig.prometheusOnManagementPort`); on the
   application port it stays behind the default-deny rule. A Prometheus server on the same host
   scrapes it; none is installed yet.
5. **Backups are taken as the `postgres` superuser.** The app role is subject to forced row-level
   security, so `pg_dump` as that role is refused. A superuser is exempt and needs no password. The
   restore drill restores into a throwaway database and checks the schema.
6. **Manual deploys.** Code is shipped with `git archive` over SSH and rebuilt with `docker compose`;
   there is no CD pipeline, so the server holds no GitHub credentials and CI holds no server access.
7. **Real email through an SMTP provider** (Brevo, free tier) with the sending domain authenticated
   by DKIM and DMARC. A local catch-all inbox (Mailpit) serves development and is an optional
   `demo` compose profile.
8. **PostgreSQL 16 on the demo server.** No migration uses 17-only SQL and tests run on 17, but
   production should run 17 (the locked stack).

## Consequences

- Rolling back code is redeploying an older commit; migrations only move forward (CLAUDE.md §3).
- A single server is a single point of failure; backups live on the same machine until an off-site
  copy is configured. Uploaded files and the encryption key are not in the database dump.
- No image vulnerability scan, dependency bot or deploy automation exists yet (CLAUDE.md §6 A06).
- Super Admin access is restricted by IP allowlist, which is only as stable as the operator's address.
