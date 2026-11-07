# 0001 — Modular monolith with shared-schema multi-tenancy

## Status

Accepted

## Context

Caderly serves multiple tenants (a first customer plus a handful of pilot companies) from one deployment. At this scale, schema-per-tenant or database-per-tenant adds operational overhead (migrations × N, connection pool sizing, cross-tenant reporting complexity) without a corresponding benefit — see PRD §20.

## Decision

Single Postgres database, single shared schema (`caderly_hr`) holding all tenants' rows. Every tenant-scoped table carries a `tenant_id` column and a Postgres Row-Level Security policy (`USING (tenant_id::text = current_setting('app.tenant_id', true))`) as the last line of defense. A Hibernate `@Filter` scopes JPA queries to the current tenant by default; `TenantContext` (a `ThreadLocal<UUID>`) and `TenantResolutionFilter` populate it per-request from the subdomain. See CLAUDE.md §5 for the full enforcement contract.

## Consequences

- One migration path, one connection pool, cross-tenant reporting (super admin) is a normal query.
- Every new tenant-scoped entity must extend `TenantAwareEntity` and ship an ArchUnit test proving it — a missed RLS policy or filter is a data leak between customers, not just a bug.
- Bypassing tenancy (super admin, system jobs) must go through `TenantContext.runAsSystem(...)` and be audited, never a raw query.
