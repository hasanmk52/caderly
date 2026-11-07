# ADR 0019 — Super Admin console: dual-realm security, no RLS bypass, and audited impersonation

**Status:** Accepted
**Date:** 2026-09-27
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0003 (tenant isolation enforcement), ADR 0004 (native Hibernate multitenancy),
ADR 0005 (system-scoped infrastructure tables), ADR 0006 (identity/session management), ADR 0017
(audit log implementation)

---

## Context

Phase 1.13 built the Super Admin console per PRD §6.12 (FR-12.1–FR-12.5), §23.2's `/superadmin/**`
endpoints, §26's permissions matrix, and §27's architecture diagram: Hasan (the one Super Admin)
provisions a tenant + first Admin in one form submit, suspends/deletes a tenant, and impersonates a
tenant's Admin for support — all from a console with its own authentication realm and an IP
allowlist. This required settling a question ADR 0003/0004 both explicitly deferred to this phase
(whether a real cross-tenant RLS-bypass strategy is needed), and the build surfaced two real,
confirmed-exploitable vulnerabilities during review, an Important session-management gap, and a
UI-layer bug — all fixed before this branch merged. This ADR records the design actually shipped,
including that history, not an idealized version of it.

## Decisions

### A. A second `SecurityFilterChain`, not a role

`superadmin.SuperAdminSecurityConfig` defines a distinct chain matched on `/superadmin/**`, backed
by `SuperAdminDetailsService` over the pre-existing `super_admin` table (`BaseEntity`, no
`tenant_id`, no RLS — the same system-scoped category as `email_outbox`/`audit_entry`, ADR 0005
decision B). A Super Admin is not a member of any tenant: there is no `AppUser`, no `Employee`, no
RLS-protected row to hang a "role" off of. Modeling this as a role on the existing tenant principal
would require one authentication realm to carry both a tenant-scoped identity and a cross-tenant one
at once — incoherent under RLS (which tenant would `app.tenant_id` be set to for that session?) —
and would leave the whole cross-tenant surface one misapplied `@PreAuthorize` check away from
exposure, rather than behind a structurally separate credential and session boundary. This is
exactly the class of change CLAUDE.md §12 flags as needing to stop and confirm before building, and
it was confirmed before implementation began.

Adding a second `UserDetailsService` bean (`SuperAdminDetailsService` alongside the existing
`AppUserDetailsService`) broke Spring Security's implicit `DaoAuthenticationProvider`
autoconfiguration for **both** realms — `InitializeUserDetailsManagerConfigurer` only auto-wires a
provider when exactly one such bean exists in the context. This was anticipated by Task 3's brief
and confirmed as the actual cause of a 14-test cascading failure introduced by Task 2 (8
`AuthenticationFlowTest`, `MdcCorrelationTest`, 5 Playwright E2E). The fix, and the design now in
place: both chains wire their `AuthenticationProvider` explicitly rather than relying on Boot's
single-bean autoconfiguration — the tenant chain keeps its provider inside a per-chain
prototype-scoped builder (parent resolves `null`), and the Super Admin chain builds its own
`ProviderManager` by hand, bypassing the shared builder entirely. Full-suite green was a completion
condition for Task 3, not a nice-to-have.

**IP allowlist:** `SuperAdminIpAllowlistFilter`, configured via the `caderly.superadmin.ip-allowlist`
env var, guards the whole `/superadmin/**` chain and runs before authentication — fail-closed, a
non-allowlisted IP never reaches the login form. This is the console's actual second factor for this
phase (see MFA deferral, decision F). `common.ClientIpResolver` trusts the first `X-Forwarded-For`
hop unconditionally, which was already an accepted tradeoff for the tenant-login rate limiter; now
that the allowlist is billed as a real security control, `application.yml` documents next to
`caderly.superadmin.ip-allowlist` that the reverse proxy (Caddy) must **set**, not append,
`X-Forwarded-For`, and that the app port must be bound to loopback only.

**Rate limiting:** `RateLimitFilter` gained a Super-Admin-specific branch (same 10/min/IP mechanism
as tenant login) rather than a second, parallel limiter implementation.

### B. ADR 0003/0004's deferred cross-tenant RLS-bypass (`BYPASSRLS`) work is *not* needed here

ADR 0003 named this exact question and deliberately left it open: *"`runAsSystem` is app-layer-only
for now. It skips the Hibernate filter, but the app role remains subject to RLS, so an unscoped
system read of tenant-scoped tables returns zero rows in dev/prod. That is safe-by-default and
acceptable until the Super Admin console (Phase 1.13) needs real cross-tenant reads — at which point
a deliberate `BYPASSRLS`-style strategy gets its own ADR."*

This phase closes that question with "no, not for this scope," for three reasons traced against the
actual shipped code rather than assumed:

1. **The `tenant` table itself carries no RLS.** `Tenant` does not extend `common.BaseEntity` or
   `TenantAwareEntity` (ADR 0003) — it has no `tenant_id` column and no RLS policy at all. The
   console's own primary object of work — listing, creating, suspending, soft-deleting tenants — is
   an ordinary unscoped read/write against a table RLS was never applied to. There is nothing here
   to bypass.
2. **Tenant provisioning sets `TenantContext` to the real new tenant, it does not bypass RLS to
   write into it.** `TenantProvisioningService`/`TenantFacade.createTenant` uses
   `TenantContext.runAsSystem(...)` only for the parts that touch non-RLS tables (creating the
   `Tenant` row itself); before writing the new tenant's first `AppUser`/`Employee`, it sets
   `TenantContext` to that tenant's own freshly-minted id and clears it in a `finally` — the same
   set/try/finally-clear idiom `EmployeeTerminationJob` already established for real (non-system)
   per-tenant work. Those writes are RLS-compliant by construction: Postgres enforces the policy
   normally, against the correct tenant, because the session variable is genuinely set to that
   tenant — not skipped.
3. **Impersonation never reads cross-tenant data in one query either.** The console mints a
   short-lived ticket naming a target tenant and user; redemption is a browser redirect to a `GET`
   on the *target tenant's own subdomain*, inside the ordinary tenant-facing `SecurityFilterChain`,
   where `TenantResolutionFilter` resolves `TenantContext` exactly as it would for any real login.
   The resulting session is an entirely normal, already-RLS-compliant tenant session built from the
   real Admin's own `AppUser` row. "Impersonating" is a marker on the principal's `roleNames()`
   (`SUPERADMIN_IMPERSONATING`) consumed only by the audit listener — it widens no data access and
   bypasses nothing.

Every database access the Super Admin console makes is therefore either against a table RLS was
never applied to, or delegated into a real, single-tenant session RLS already governs correctly. No
feature in this phase's scope needs to join or read tenant-scoped rows from more than one tenant in
a single query, so the `BYPASSRLS` work ADR 0003/0004 deferred stays deferred. Revisit only if a
future feature genuinely needs that — the most likely candidate, a cross-tenant audit *viewer* on
top of the already system-scoped `audit_entry` table, is explicitly out of this phase's scope (see
`CURRENT_PHASE.md`'s carried-forward items) and was not built here.

### C. Two vulnerabilities found and fixed during Task 3's review

**Session/security-context isolation between the two realms (Critical).** Neither
`SecurityFilterChain` originally configured its own `SecurityContextRepository`, so both shared one
`HttpSession`/`SPRING_SECURITY_CONTEXT` key. A Super Admin session satisfied the tenant chain's
`anyRequest().authenticated()` check, so any tenant controller gated only by a bare
`@PreAuthorize("isAuthenticated()")` with no principal type dependency (e.g. `FilesController`)
would serve that tenant's data to a browser holding only a Super Admin login — with **no**
impersonation flow, no audit trail, routing entirely around the audited-impersonation design Task 5
built. The pre-existing cross-realm test only probed a `hasRole('ADMIN')` endpoint and passed on the
role check alone, without exercising realm separation at all.

Fix: the Super Admin chain now uses its own `HttpSessionSecurityContextRepository` with a distinct
session key (`SUPERADMIN_SECURITY_CONTEXT`), wired via
`.securityContext(sc -> sc.securityContextRepository(repo))`. A genuine regression test targets the
exact exploit (`FilesController`, confirmed `@PreAuthorize("isAuthenticated()")` with no principal
dependency): a Super-Admin-authenticated session must not get `200` on a tenant page. A residual
concern — both chains' `DelegatingSecurityContextRepository` still writing to the same default
`RequestAttributeSecurityContextRepository` request-attribute name — was traced end-to-end and
confirmed closed as a real data path: that repository is only populated on the login POST itself and
only readable via a container `ERROR` dispatch to `/error`, which is `permitAll()`, carries no
`TenantContext`, and renders no principal or tenant data.

**Percent-encoding bypass of the IP allowlist and rate limiter (confirmed exploitable).**
`SuperAdminIpAllowlistFilter` and `RateLimitFilter`'s Super Admin branch both matched the **raw**
request URI (`getRequestURI()`), while Spring Security 7's `PathPatternRequestMatcher` (used by
`securityMatcher`/`loginProcessingUrl`) matches the **decoded** path. A request to
`POST /%73uperadmin/login` (percent-encoded `s`) did not match either filter's raw-URI prefix check,
so it skipped both the IP allowlist and the rate limiter entirely, while Spring Security's own
decoded-path matching still routed it to the real login processing — defeating both defenses down to
password-only. This was verified with a real before/after repro (a non-allowlisted IP completing a
login via the percent-encoded path returned `302` before the fix, `403` after) rather than left as a
theoretical concern.

Fix: a new `common.RequestPathResolver.decodedPath()` helper, used only by the Super Admin-specific
branches of the IP allowlist filter and the rate limiter (the pre-existing tenant-login paths in
`RateLimitFilter` were left untouched — out of scope for this fix). The decoding semantics were
checked against `PathPattern`'s actual behavior across double-encoding, path parameters, case
sensitivity, and malformed escapes, with no over- or under-gating introduced. `common
.TenantResolutionFilter.shouldNotFilter` has the same latent raw/decoded mismatch one file over —
harmless today only because the allowlist and rate limiter already judge the decoded path first, and
because no `context-path` is configured anywhere; noted here as a pattern worth remembering, not
fixed in this phase (out of scope for Task 3's diff).

Both findings are exactly the kind of non-obvious design decision CLAUDE.md §4/§12 says an ADR
should capture — anyone touching `/superadmin/**` security later needs to know the session-key
partitioning and decoded-path matching are load-bearing, not incidental.

### D. The impersonation ticket mechanism — in-JVM, single-use, 60-second TTL

`identity.ImpersonationTicket` is a plain record (`superAdminId`, `tenantId`, `targetUserId`,
`expiresAt`) — **never persisted**. `identity.ImpersonationService` holds it in an in-JVM Caffeine
cache (`maximumSize(1000)`, `expireAfterWrite(Duration.ofSeconds(60))`), mirroring
`RateLimitFilter`'s existing bucket-cache style. `mint(...)` generates the cache key via
`common.SecureToken.generate()` (reused purely for its 32-byte `SecureRandom` generation — the token
is never hashed, since there is nothing persisted to hash against). `redeem(...)` calls
`tickets.asMap().remove(token)`, making the ticket single-use **by construction** — removal, not a
plain `get` — so a second redemption attempt always fails regardless of expiry, and checks
`expiresAt` only after removal.

**Why not a DB table:** a durable outbox-style record (CLAUDE.md §6a) exists to guarantee an
external side effect is not silently lost — this ticket is the opposite: a short-lived, single-use
*credential* that is supposed to disappear the moment it's used or 60 seconds pass, whichever comes
first. Losing an in-flight, unredeemed ticket to a JVM restart costs nothing worse than Hasan
clicking Impersonate again; there is no data-loss risk and no retry semantics that would benefit from
durability. The actual audit trail impersonation needs — a durable, queryable record that a session
happened — is written separately and durably to `audit_entry` via
`audit.EntityAuditListener.recordManualEvent(...)` (start and end events, `entity_type =
"ImpersonationSession"`, correlated by a shared `entity_id`), independent of the ticket's own
lifetime. The ticket only needs to survive long enough to redirect a browser once; the audit record
is what needs to survive.

Task 5's implementer made three deliberate deviations from the original brief, each independently
verified correct by review rather than accepted on trust:

- **Endpoint path `/impersonate`, not the brief's literal `/superadmin-impersonate`.** Both
  `TenantResolutionFilter.shouldNotFilter` and `SuperAdminIpAllowlistFilter` match bare
  `startsWith("/superadmin")` (no trailing slash) — the brief's own proposed path would have skipped
  tenant resolution entirely *and* landed behind the operator IP allowlist, breaking the flow twice
  over. The brief was wrong here; the deviation was mandatory.
- **`ImpersonatedAdminPrincipal extends AppUserPrincipal`**, rather than wrapping/delegating to it.
  Verified against Spring Security's actual `AuthenticationPrincipalArgumentResolver`:
  `@AuthenticationPrincipal` resolution is assignability-based against the *declared* parameter type
  and silently injects `null` on a mismatch. 27 controller methods across 7 controllers declare
  `@AuthenticationPrincipal AppUserPrincipal`; a delegating wrapper of a different type would have
  silently broken nearly every tenant page during an impersonated session. `roleNames()` is
  correctly overridden (→ `SUPERADMIN_IMPERSONATING`); `getAuthorities()`/`roles()` are correctly
  *not* overridden, so `@PreAuthorize` behaves identically to a normal Admin login during
  impersonation — proven by a live `GET /admin/users` returning `200` mid-session.
- **A new shared `SecurityContextRepository` bean wired on the tenant chain only**, not both chains
  as the brief literally specified. Getting this wrong would have silently reopened the C1
  vulnerability above. Verified three ways: `SuperAdminSecurityConfig` is byte-identical to before
  this change and still builds its own distinct-key repository independently; Spring does not
  auto-detect a `SecurityContextRepository` bean into `HttpSecurity`'s shared objects (no such
  auto-wiring path exists in `HttpSecurityConfiguration`); and the new bean's composition order was
  checked against Spring Security 7.1.0's actual default ordering and matches it correctly.

### E. `SessionRegistry` gap — impersonated sessions weren't revocable (Important, fixed)

Impersonated sessions are established via `securityContextRepository.saveContext(...)` directly,
bypassing `SessionAuthenticationStrategy` — `SessionManagementFilter`'s `containsContext` check skips
registration once a context already exists. Consequence: `SessionRevoker.revokeAllFor(userId)`, the
CLAUDE.md §6 A07 control for "session revocation on password change and role change," could not see
or kill an in-flight impersonated session — a password change or termination on the impersonated
Admin's account would not end that session. This is not a privilege-escalation path (the ticket must
already have been legitimately minted by a Super Admin), but it is a real, documented security
control silently not firing, which matters directly under A07.

Fix: `ImpersonationController` injects the existing `SessionRegistry` bean and calls
`registerNewSession(...)` immediately after `saveContext(...)`. No further change was needed for
revocation to actually work, because `ImpersonatedAdminPrincipal extends AppUserPrincipal` (decision
D) — `SessionRevoker`'s existing `instanceof` scan finds it automatically. Verified end-to-end, not
just by reading the diff: the re-review ran `ImpersonationControllerTest` directly and observed a
real `SessionRevoker : Revoked 1 session(s) for user <id>` log line fire from an actual
`PasswordChangedEvent`.

### F. MFA deferral for Super Admin

No `TotpService` (or any TOTP implementation) exists anywhere in this codebase yet — MFA is planned
per CLAUDE.md's locked stack but not built for any realm, tenant or Super Admin. Building it
first for a realm with exactly one user (Hasan) ahead of the tenant realm that will need it for
every tenant's Admin role would be solving the smaller problem before the real one. For this phase,
the IP allowlist (decision A) plus the 10/min/IP rate limit are the actual controls: an attacker
needs both a stolen credential *and* an allowlisted network path to reach the login form at all,
which is a materially higher bar than password-only. Revisit when `TotpService` is built for the
tenant realm — wiring it into the Super Admin realm at that point is a small addition, not a new
mechanism.

### G. Bootstrap mechanism — idempotent, env-var gated, runs everywhere

`superadmin.SuperAdminBootstrap` (`implements ApplicationRunner`, no `@Profile` restriction — it
runs in every environment, not just dev) reads `caderly.superadmin.bootstrap-email` /
`bootstrap-password` (`${CADERLY_SUPERADMIN_EMAIL:}` / `${CADERLY_SUPERADMIN_PASSWORD:}`, both
blank-defaulted). If either is blank, it logs at `info` and no-ops — a deliberately different
failure posture from the fail-*closed* IP allowlist, since a missing bootstrap credential simply
means "don't bootstrap," not a security gap. Otherwise, inside
`TenantContext.runAsSystem("bootstrap super admin", ...)`, it checks `SuperAdminRepository
.count() > 0` (simplest correct check — the table holds at most one row in practice) before
inserting, making repeated application starts with the same env vars safe. This is the same
mechanism used to create the first Super Admin in every environment (dev, the first customer's production, any future
pilot-tenant deployment) — not a one-off manual `INSERT` or a dev-only seeder.

### H. htmx + Spring MVC redirect gotcha (Task 6, UI-layer finding)

The original `SuperAdminTenantController.toggleSuspend`/`delete` returned a Spring `redirect:` paired
with an `HX-Redirect` response header — a plausible-looking htmx idiom that does not actually work:
per the fetch/XHR spec, a browser's XHR (htmx's default transport) transparently follows a
same-origin redirect *before* any JavaScript response handler runs, so htmx never sees the
intermediate `302`'s `HX-Redirect` header at all — only the terminal `200` HTML page, which falls
back to htmx's default swap (innerHTML into the triggering button itself, since neither button
declared `hx-target`/`hx-swap`). The two most-used row actions (Suspend, Delete) would have rendered
broken UI in production while the underlying state change still succeeded server-side — no data-
safety issue, purely a UI-feedback defect, and one not caught by any MockMvc test since
`redirectedUrl(...)` inspects the immediate unfollowed response and never simulates browser-level
redirect-following.

Fix: dropped the redirect + `HX-Redirect` combination entirely in favor of this repo's own
established convention (`admin/organization.html`'s pattern) — both actions now return the
re-rendered `superadmin/tenants :: content` fragment (`hx-swap-oob="true"`, freshly recomputed via
`populateTable(model)` after the mutation) with `hx-target`/`hx-swap` on the row buttons, a plain
`200` with no redirect. This is a smaller finding than the two in decision C, but worth recording as
a general gotcha: **htmx cannot observe a response header on a redirect its own XHR call followed
transparently** — the fix is always either a plain `200` fragment (used here) or a genuine top-level
form navigation (used elsewhere in this same phase for the Impersonate action, decision D, where a
real `<form method="post">`'s browser-native navigation was the correct choice specifically because
it needed to cross origins, which XHR/CORS would have blocked). The two situations look similar but
need opposite fixes; conflating them is the mistake to avoid next time.

### I. Deviations and open questions carried forward, not resolved here

- **`SuperAdminAuthController` (`GET /superadmin/login`) was not in Task 6's file list but is
  required.** Spring Security's `formLogin().loginPage(...)` only *redirects* unauthenticated
  requests to that URL — it never serves the `GET` itself. This mirrors `web.AuthController
  .loginPage()`'s identical pattern for the tenant realm and falls under the existing
  `/superadmin/**` chain automatically. A necessary addition, not scope creep.
- **`TenantFacade` gained a single-tenant `find(UUID)` lookup**, added retroactively in Task 6 once
  `listAllForAdmin()` proved not to give a convenient single-row lookup for the impersonate/suspend/
  delete row actions. A small addendum to Task 1's original interface, not a redesign.
- **`TenantFacade.createTenant` throws `ConflictException("TENANT_SLUG_TAKEN", ...)` on a duplicate
  slug** — an interpretive addition beyond Task 1's original pseudocode, reviewed and accepted as
  in-scope (Task 6's controller needs it for its error handling).
- **A soft-deleted tenant's slug can never be reused** — `tenant.slug` has a bare `UNIQUE`
  constraint, not a partial index excluding soft-deleted rows. This is a genuine product-behavior
  question (should a deleted tenant's slug become available again after its grace period?), not a
  bug, flagged during Task 1 and never resolved. Any fix requires a schema change and its own ADR;
  out of this phase's scope. Recorded here as the permanent reference for that open question.
- **`ImpersonationService.findAnyAdmin(UUID tenantId)` picks the first ACTIVE Admin `findAll()`
  returns** when a tenant has more than one — an accepted MVP simplification (no admin-picker UI was
  in scope). Revisit if a real pilot tenant with multiple Admins needs to choose which one to
  impersonate.
- **`errorDetail()`/`baseUrl()` are duplicated as small package-private helpers** in
  `superadmin.SuperAdminTenantController`, reproducing logic that already exists in `web.WebMessages`
  and `web.RequestTenant` but is not visible outside their own package. Reasonable duplication at
  this scale; not extracted into a shared seam in this phase.
- **`SuperAdminSecurityConfig`'s Javadoc still misstates Spring's default `SecurityContextRepository`
  composition order** (functionally equivalent, comment-only, pre-existing before Task 5 and
  correctly left alone per CLAUDE.md §12 rather than touched out-of-scope).

## Consequences

**Positive:**
- The Super Admin realm is structurally isolated from the tenant realm — distinct authentication
  provider wiring, distinct session key, distinct `UserDetailsService` — not merely gated by a role
  check on shared infrastructure.
- No `BYPASSRLS` strategy, no superuser database role, and no weakening of the RLS backstop
  (ADR 0003) was needed to ship cross-tenant provisioning, suspension, deletion, or impersonation.
- Every write made during an impersonated session is durably audited with
  `actor_role = SUPERADMIN_IMPERSONATING`, correlated start-to-end by a shared `entity_id`, backed by
  the same durable `audit_entry` mechanism ADR 0017 already established — no new audit code path.
- Two real, exploitable vulnerabilities (cross-realm session bleed; percent-encoding bypass of the
  allowlist/rate-limiter) were found and closed before this branch merged, with regression tests
  targeting the exact exploit in each case, not just the surface symptom.
- Impersonated sessions are now visible to and revocable by the existing `SessionRegistry`/
  `SessionRevoker` machinery, closing a real CLAUDE.md §6 A07 gap before it shipped.

**Negative:**
- Super Admin login has no MFA yet — the IP allowlist and rate limiter are the only controls for
  this phase. Acceptable at one Super Admin user and a deployment where the allowlist is genuinely
  enforceable (loopback binding + a trusted reverse proxy); revisit once `TotpService` exists.
- A soft-deleted tenant's slug is permanently unavailable for reuse — a real product-behavior
  question with no owner yet (decision I).
- `TenantProvisioningService.provision` is deliberately non-transactional across its two steps
  (Global Constraint 1's RLS sequencing requires it), and a tenant's slug can never be reused once
  taken (the point above). Together, a first-Admin invite that fails after `createTenant` has
  already committed — a bounced address, an outbox/SMTP failure — leaves an orphaned tenant row
  permanently occupying that slug, with no compensating transaction to roll it back. Each choice is
  independently reasonable; this is the accepted cost of the two interacting, not a bug in either.
- `findAnyAdmin`'s first-match selection means a tenant with multiple Admins has no way to choose
  which one an impersonation session targets — fine for MVP, a gap for a tenant that actually has
  more than one Admin.
- The bootstrap mechanism's fail-*open* posture (blank env vars → silent no-op, not a startup error)
  means a misconfigured deployment can silently have no Super Admin at all rather than failing loud
  — a deliberate tradeoff versus the allowlist's fail-*closed* posture, worth remembering as an
  asymmetry in this phase's design.

## References

- PRD §6.12 (FR-12.1–FR-12.5), §23.2 (`/superadmin/**` endpoints), §26 (permissions matrix), §27
  (architecture diagram)
- CLAUDE.md §5 (multi-tenancy contract), §6 A01/A02/A07/A09 (access control, crypto, session
  revocation, audit logging), §6a (durable outbox contract — why the impersonation ticket is
  deliberately *not* built that way), §12 (change classes requiring confirmation before proceeding)
- ADR 0003 (tenant isolation enforcement — names the deferred `BYPASSRLS` question this ADR closes)
- ADR 0004 (native Hibernate multitenancy — `TenantContext.runAsSystem`/`TenantIdentifierResolver`
  mechanics this phase reuses without modification)
- ADR 0005 (system-scoped infrastructure tables — `super_admin`'s tenancy shape)
- ADR 0017 (audit log implementation — `EntityAuditListener`'s raw-JDBC insert mechanism this phase
  extends with `recordManualEvent`)
- `docs/CURRENT_PHASE.md` (Phase 1.13 scope as originally stated, and its carried-forward items)
