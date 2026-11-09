# ADR 0017 — Audit log implementation, MDC/structured logging, and (email+IP) lockout re-key

**Status:** Accepted
**Date:** 2026-09-18
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0005 (system-scoped infrastructure tables), ADR 0006 (identity/session management, decision B)

---

## Context

Phase 1.11 (Audit Log) needed several related decisions the PRD and Implementation Plan named but
did not fully settle: how `audit_entry`/`login_audit` sit relative to the tenancy model, which
entities an audit listener actually covers, how to capture accurate before/after state from JPA
lifecycle callbacks without breaking Hibernate's flush, how MDC-based log correlation actually gets
wired (it turned out not to exist yet, despite CLAUDE.md implying otherwise), and — once
`login_audit` existed — whether to finally close the (email + IP) lockout gap ADR 0006 deliberately
left open.

## Decisions

### A. Tenancy shape — confirms ADR 0005, not a new decision

`audit_entry` and `login_audit` are system-scoped infrastructure tables: `BaseEntity`, not
`TenantAwareEntity`; no `@TenantId`; no RLS. ADR 0005 decision B already named this category and
explicitly anticipated these two tables. `audit.AuditListener` fires from JPA flush callbacks that
run for any tenant-scoped write regardless of whether a tenant is cleanly resolved (and
`login_audit` must record an attempt against an unknown email, which by definition has no
tenant-scoped account to key against) — the same "system code touches this without necessarily
having a clean per-tenant read" argument that justified `email_outbox`. `audit.AuditAdminService`
and `audit.LoginAuditAdminService` are the explicit tenant boundary for their respective Admin
viewers, exactly as `notifications.NotificationAdminService` is for `email_outbox`.

Super Admin's cross-tenant view (PRD §26) is not built in this phase — no Super Admin console
exists yet (Phase 1.13). The system-scoped shape makes that future addition trivial: the
cross-tenant query is the same query with the tenant-equality predicate omitted.

### B. `AuditListener` entity scope — every `TenantAwareEntity` except `PasswordResetToken`

`@EntityListeners(EntityAuditListener.class)` is applied to all 18 `TenantAwareEntity` subclasses
except `identity.PasswordResetToken`. A password-reset token is a high-churn, no-editable-state
security artifact — its create/delete events carry no business-meaningful diff, the same "noise,
not signal" reasoning CURRENT_PHASE.md already applied to `EmailOutbox`'s own status transitions
(which are excluded by construction, since `EmailOutbox` isn't `TenantAwareEntity` at all).

### C. Before/after capture — `@PostLoad` snapshot diffed at `@PostUpdate`, written via raw JDBC

JPA's `@PostPersist`/`@PostUpdate`/`@PreRemove` callbacks only see an entity's *current* in-memory
field values — Hibernate has already merged new values into the Java object by the time any
callback fires, so there is no framework-native way to see what a field held immediately before a
change. Every service in this codebase follows a load-mutate-flush pattern (private setters, intent
methods — CLAUDE.md §10), so a snapshot taken on `@PostLoad` and diffed at `@PostUpdate` is reliable.
`@PostUpdate`, not `@PreUpdate`: Hibernate runs `@PreUpdate` whenever a flush dirty-checks the
entity, including an auto-flush before a query on another table that writes nothing, so one real
UPDATE could be logged twice. `@PostUpdate` runs once per UPDATE executed.
The snapshot itself lives in a `@Transient` (and Java-`transient`) field on `TenantAwareEntity`
(`auditSnapshot`) rather than a per-entity field repeated 18 times or a bespoke marker interface —
one field on the shared base every audited entity already extends.

The insert itself is a raw `JdbcTemplate` statement, not `entityManager.persist(...)`. Calling back
into the same `EntityManager` to persist a different entity from inside a JPA flush callback is
documented Hibernate territory to avoid — the flush's action queue for the session has already been
built, and inserting into it mid-flush is unsupported, not merely discouraged. A plain JDBC insert
runs on the same transaction-bound `Connection` (Spring binds it by `DataSource`) without touching
the Hibernate session at all, so it still commits or rolls back with the surrounding transaction —
this is still a synchronous in-transaction write per CLAUDE.md §6a, not an outbox, just without
going through Hibernate's own persistence context.

**Schema-qualification gotcha (found during implementation):** the raw insert had to be written as
`INSERT INTO caderly_hr.audit_entry ...`, not the bare table name. Hibernate-generated SQL is
rewritten by `hibernate.default_schema`; a raw `JdbcTemplate` statement is not, and a JDBC
connection's default `search_path` does not include `caderly_hr`. `people.EmployeeRepository`'s
existing native recursive-CTE query already documents this exact gotcha for native JPQL; this is
the same problem showing up for raw JDBC.

**Field redaction:** a field converted by `common.CryptoConverter`, or whose name contains
"password", "secret", "token", or "hash" (case-insensitive), is replaced with a fixed `[REDACTED]`
marker in the JSON snapshot rather than its real value or being dropped — the audit trail should
show that a secret field changed without ever holding the secret (CLAUDE.md §6 A02). Relationship
fields (`@ManyToOne`/`@OneToMany`/`@OneToOne`/`@ManyToMany`/`@ElementCollection`) are skipped
entirely rather than followed, to avoid lazy-proxy initialization outside a session and infinite
recursion on bidirectional associations — an accepted MVP simplification, since the one relation
most likely to matter (an employee's manager) already has its own audited history table
(`people.EmployeeManagerHistory`).

**Naming:** the listener class is `audit.EntityAuditListener`, not `AuditListener` — Spring Boot
Actuator's own `AuditAutoConfiguration` registers a bean literally named `auditListener`
(lowercase-first default bean name), and a `@Component class AuditListener` collided with it,
surfacing only as a `BeanDefinitionOverrideException` at full application-context startup. Worth
recording since the failure mode gives no hint that the cause is a naming collision with unrelated
framework infrastructure.

### D. Cross-module dependency shape (`audit` ↔ `identity` ↔ `security`)

`identity.LoginAttemptService` needs `audit.LoginAuditRepository`/`LoginAuditService` (the lockout
re-key's failure count, decision E below) — `identity` → `audit`. `security.LoginAttemptListener`
needs both `identity.LoginAttemptService` and `audit.LoginAuditService` — `security` → `audit` and
`security` → `identity`. For `audit.EntityAuditListener` to attribute a write to an actor without
creating a package cycle back through `identity`, `common.AuditActor` is a minimal interface
(`actorId()`, `roleNames()`) that `identity.AppUserPrincipal` implements — `audit` depends on
`common` (which nothing depends on) to read it, never on `identity` directly. The same reasoning
put `common.ClientIpResolver` in `common` rather than `security`, since both `audit` and `identity`
need IP resolution and neither may depend on `security`.

### E. Lockout re-keyed to (email + IP), superseding ADR 0006 decision B

ADR 0006 decision B deliberately keyed lockout on the user alone because the per-attempt,
IP-bearing record that decision was waiting on (`login_audit`) didn't exist yet. It now does.
`identity.LoginAttemptService.evaluateAfterFailure` queries
`audit.LoginAuditRepository.countRecentFailures` — failed attempts for one (tenant, email, ip)
triple in the trailing 15-minute window, backed by a new partial index
(`idx_login_audit_lockout ... WHERE NOT success`) — and locks the account once that count reaches 5.

The *trigger* is scoped by (email, ip); the *result* is still "this account is locked" — Spring
Security's `UserDetails.isAccountNonLocked()` is inherently per-user, so that remains the correct
enforcement seam. Consequence: 5 failures from one IP still locks the account, but 4 failures from
IP-A plus 4 from IP-B no longer does (the old per-user counter would have locked at the 5th failure
regardless of origin). `security.RateLimitFilter`'s existing 10/min/IP limit is what still bounds a
single IP spraying many different accounts.

`AppUser.failedLoginCount`/`failedLoginWindowStart` — both the Java fields and their use — are
removed; the DB columns (`NOT NULL DEFAULT 0` / nullable) are left in place unused rather than
risking a destructive drop-column migration for this phase. `AppUser` keeps a single `lock(Instant
until)` intent method; the counting decision moved entirely to `LoginAttemptService`.

### F. Login audit failure-reason disambiguation

Spring Security's `hideUserNotFoundExceptions` (default `true`) collapses "unknown email" and
"wrong password" into the same `AuthenticationFailureBadCredentialsEvent`, deliberately, so a
timing or event-type difference cannot leak which case occurred to an attacker. `security.
LoginAttemptListener`'s handler for that event does its own tenant-scoped `AppUserRepository
.findByEmail` lookup to tell the two apart for the **audit row and lockout decision only** — the
login page's response stays identical either way, which is the actual security property that
matters. `AuthenticationFailureLockedEvent`/`AuthenticationFailureDisabledEvent` map to their own
distinct `LoginAudit.FailureReason` values and do **not** run the lockout evaluation — counting
those would either extend an existing lock indefinitely or attempt to lock an account that already
cannot authenticate.

### G. MDC / structured logging — built, not merely confirmed

CURRENT_PHASE.md and CLAUDE.md §6 A09 implied structured JSON logging and MDC correlation
(`requestId`/`tenantId`/`actorId`) already existed from an earlier phase. Investigation found
neither did: `application.yml`'s structured-logging block was present but commented out, and there
was not a single `MDC.put` call anywhere in the codebase. This phase built both:

- `tenant.TenantResolutionFilter` (already the first filter in the chain) now also generates/reads
  an `X-Request-Id` header, puts `requestId` into MDC before tenant resolution runs (so it covers
  the 404/503 denials too), and puts `tenantId` once a tenant resolves.
- `security.ActorMdcFilter`, a new filter registered *after* Spring Security's own chain (order 0,
  well after Boot's `-100`), puts `actorId` into MDC once `SecurityContextHolder` holds an
  authenticated `common.AuditActor` principal.
- `application.yml` now enables `logging.structured.format.console: logstash` — Spring Boot 4.1's
  built-in structured-logging feature, no new dependency, which folds MDC entries into the emitted
  JSON automatically. The `test` profile explicitly disables it (`application-test.yml`) so a
  failing test's console output stays human-readable; MDC population itself is asserted directly in
  `security.MdcCorrelationTest`, not by parsing log lines.

### H. JSON serialization — Jackson 3.x is already present, under `tools.jackson.*`

The audit snapshot needs a JSON library. Initial compilation assumed `com.fasterxml.jackson.
databind`/`core` and failed — this codebase has no JSON library at all reachable under those
coordinates, since it has never exposed a JSON REST API. Investigation found Jackson **is**
transitively present via `spring-boot-starter-webmvc` → `spring-boot-starter-jackson`, but as
Jackson 3.x, which relocated its Maven coordinates and Java packages from `com.fasterxml.jackson.*`
to `tools.jackson.*` (the `jackson-annotations` module is the one holdout still under
`com.fasterxml.jackson.core`). Jackson 3 also made `JacksonException` an unchecked
`RuntimeException`, replacing the old checked `JsonProcessingException`. No new Maven dependency
was needed — only correct imports (`tools.jackson.databind.ObjectMapper`,
`tools.jackson.core.JacksonException`).

## Consequences

**Positive:**
- Every write across 17 real business/security entities is now durably auditable with accurate
  before/after state, without any hand-written per-entity audit code.
- The lockout mechanism finally matches PRD §19.1's original (email + IP) intent.
- MDC-based log correlation is real infrastructure now, not an assumption other phases could have
  silently relied on without it existing.
- The `common.AuditActor`/`common.ClientIpResolver` seams keep `audit`, `identity`, and `security`
  acyclic despite needing to reference each other.

**Negative:**
- `AppUser.failed_login_count`/`failed_login_window_start` are permanently dead columns unless a
  future migration drops them.
- Relationship-field changes (e.g., an employee's department reassignment via the FK itself) are
  not captured in the audit diff — only scalar column changes are. Acceptable for MVP; revisit if a
  real need for relation-level audit diffs surfaces.
- Actor display in the Admin write-audit viewer shows a raw user id, not an email, since resolving
  it would require `audit` to depend on `identity` (a cycle). Acceptable MVP simplification.

## References

- PRD §18 (Audit Logs — Detailed), §21 (Database Schema), §19.1 (lockout), §26 (permissions matrix)
- CLAUDE.md §5 (multi-tenancy), §6 A02/A07/A09, §6a (durable outbox contract), §10 (entity style)
- ADR 0005 (system-scoped infrastructure tables), ADR 0006 (identity/session management)
- `docs/CURRENT_PHASE.md` (Phase 1.11 scope as originally stated)
