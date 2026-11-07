# ADR 0011 — Manager role sync: deriving MANAGER from the org chart, not granting it once at invite

**Status:** Accepted
**Date:** 2026-08-22
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0005 (`user_role` synthetic PK and system-scoped infrastructure tables), ADR 0009 (`people`↔`timeoff` event wiring), ADR 0010 (leave requests and approvals)

---

## Context

Found while manually testing Phase 1.6 (Leave Requests + Approvals): a real manager, set via `employee.manager_id`, got a 403 on `/for-action`. Root-caused to a gap that predates Phase 1.6 entirely.

**PRD §5, line 96** states the intended design explicitly: *"Managers are derived: user X is a 'manager' if any other user has `manager_id = X.id`."* Manager-ness is meant to be a computed property of the org chart.

**What was actually built (Phase 1.2/1.4):** `MANAGER`/`EMPLOYEE`/`ADMIN` are static rows in `user_role`, granted exactly once, at invite time — `EmployeeService.create` calls `InviteService.invite(email, Set.of(Role.EMPLOYEE), ...)`, hardcoded to `EMPLOYEE` only. `EmployeeService.reassignManagerInternal` — the only place `manager_id` ever changes, whether at creation (via `applyManagerChange`) or later (via the admin-facing `reassignManager(UUID, UUID)`) — never touches `AppUser` or `user_role` at all. `LeaveApprovalController`'s `@PreAuthorize("hasRole('MANAGER')")` (the first feature in this codebase to actually gate something on the MANAGER role) checks that static grant, which has no relationship to the org chart.

No admin UI exists to grant or revoke a role after invite time either — `AdminUserController`/`/admin/users` is explicitly documented as read-only.

Checked directly, not assumed: no later phase (0 through 4 in `docs/Caderly_Implementation_Plan.md`, cross-checked with a full-document grep for "role") plans to close this. It is a genuine, unaddressed gap between the PRD's stated intent and the implementation, not upcoming work being duplicated.

## Decision

### A. Event-driven sync at the point `manager_id` changes, not live derivation at authorization time

**Rejected alternative:** replace every `hasRole('MANAGER')` check with a live "does anyone report to me" query. Rejected because it touches every `@PreAuthorize` site using the role, loses Spring's automatic `ADMIN > MANAGER` role-hierarchy expansion (would need reimplementing per site, by hand, with more room to get it wrong), and is a materially bigger, riskier change to the authorization model itself for what is fundamentally a data-sync gap, not an authorization-architecture gap.

**Chosen:** `EmployeeService.reassignManagerInternal` — the sole place `manager_id` is ever written — publishes `ManagerRoleSyncEvent` after each change. `ManagerRoleSyncListener` grants/revokes `MANAGER` on the affected `AppUser`(s) in response. The static-role model (and every existing `@PreAuthorize("hasRole('MANAGER')")` site) is left completely alone; only the thing that keeps it in sync with the org chart is new.

### B. The listener lives in `people`, not `identity`

`people` already imports `identity` directly and extensively (`EmployeeService`, `PeopleFacadeImpl` both import `AppUser`/`AppUserRepository`/`Role` with no facade in between). `identity` imports nothing from `people` — confirmed empty by grep. The one existing precedent for a cross-module event between these exact two packages, `identity`'s `UserInviteAcceptedEvent`, puts its listener (`EmployeeInviteAcceptedListener`) in `people`, specifically so `identity` never has to know `people` exists. A listener living in `identity` for a `people`-published event would invert that direction and reintroduce the exact `people`→`identity`→`people` cycle `ArchitectureTest.packages_haveNoCycles()` forbids. `ManagerRoleSyncEvent` and its listener both live in `people` for the same reason — self-published, self-consumed within one module, kept as an event anyway (rather than an inline call from `reassignManagerInternal`) so role-sync bookkeeping doesn't get hand-mixed into org-chart-reassignment logic.

### C. Plain `@EventListener`, not `@TransactionalEventListener(AFTER_COMMIT)`

Mirrors `EmployeeHiredEventListener`'s reasoning, not `EmployeeInviteAcceptedListener`'s: the role write is an internal DB write to a different aggregate (`AppUser`) that must stay consistent with the `manager_id` write it results from, and it never crosses a physical transaction boundary the way invite-acceptance does (`identity`'s transaction triggering an activation inside `people`'s own separate transaction). It stays inside `reassignManagerInternal`'s own `@Transactional` boundary rather than opening a new one after commit.

### D. One-time backfill migration, with an explicit per-tenant RLS loop

`user_role` has `FORCE ROW LEVEL SECURITY`; the DB role the app (and Flyway) connects as in dev/prod is an ordinary role, not `BYPASSRLS`. Backfilling `MANAGER` for every employee who already has reports, across every existing tenant, therefore requires looping over tenants and calling `SELECT set_config('app.tenant_id', ..., true)` before each tenant's `INSERT` — the first migration in this codebase to need that pattern. The migration is commented explicitly warning against "simplifying" it into a single cross-tenant `INSERT`, which RLS would silently restrict to whatever tenant (or none) happened to be set, not actually backfill every tenant.

## Consequences

**Positive:**
- Closes the gap with no change to the authorization model itself — every existing `@PreAuthorize("hasRole('MANAGER')")` site, and the `ADMIN > MANAGER` role hierarchy, keep working exactly as before.
- The backfill migration means existing org charts (e.g. the first tenant's real data) self-correct on the next deploy — no manual per-user SQL grant needed.
- `AppUser.revoke(Role)` now actually exists, closing a gap between `UserRole`'s class-doc comment (which already claimed "managed through `AppUser#grant` / `AppUser#revoke`") and reality.

**Negative / open (known, accepted limitation):**
- If `manager_id` is ever changed by something other than `EmployeeService.reassignManagerInternal` — a raw SQL update, or a future bulk-import path that bypasses it — the affected employee's `MANAGER` role goes stale until the next real reassignment touches them. This is the same class of eventual-inconsistency risk this codebase already accepts for `EmployeeHiredEvent`-driven initial balance grants (ADR 0009); accepted here for the same reason, not silently ignored.
- A terminated manager keeps `MANAGER` in `user_role` — this feature doesn't change that separate, pre-existing design choice (`applyTermination` disables the `AppUser` and revokes sessions, which already makes the retained role harmless: they can't log in to use it).

## Alternatives considered

**1. Live-computed authorization (see Decision A).** Rejected — bigger blast radius on the security-critical authorization layer for a data-sync problem.

**2. Add an admin UI to grant/revoke roles by hand, and leave manager assignment unlinked from roles.** Rejected: doesn't match the PRD's stated intent ("derived"), and would require every admin who reassigns a manager to remember a second, unrelated manual step — exactly the kind of gap that caused this bug in the first place.

**3. Sync roles inside `EmployeeInviteAcceptedListener` (on invite acceptance) instead of on manager reassignment.** Rejected after checking whether a manager could ever be assigned before their own `AppUser` exists: `EmployeeService.create()` calls `inviteService.invite(...)` and `employee.linkUser(userId)` synchronously in the same transaction, so every persisted `Employee` always has a linked `AppUser` from the moment it exists — this edge case doesn't arise, so there's no separate invite-time hook needed.

## References

- PRD §5 (user roles, "Managers are derived")
- `docs/Caderly_Implementation_Plan.md` §1.2 (Authentication & Users — role hierarchy/`user_role` origin), §1.4 (Employee CRUD + Profile — manager-assignment origin)
- CLAUDE.md §4 (package structure, cross-module facade rule, no cycles), §12 ("Any deviation from the PRD or Implementation Plan" — why this stopped for a decision before implementation)
- ADR 0009 (`people`↔`timeoff` event wiring — the `EmployeeHiredEvent`/`EmployeeHiredEventListener` pattern this ADR's Decision C mirrors)
- `identity.UserInviteAcceptedEvent` / `people.EmployeeInviteAcceptedListener` (the precedent for Decision B's package placement)
