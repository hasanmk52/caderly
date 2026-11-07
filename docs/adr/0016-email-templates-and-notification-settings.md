# ADR 0016 — Templated email catalog, tenant notification settings, and dropping per-tenant colour

**Status:** Accepted
**Date:** 2026-09-15
**Deciders:** Hasan (solo dev), with user sign-off on the three scope decisions below
**Relates to:** CLAUDE.md §4 (package structure, facades), §6a (durable outbox), §7 (Thymeleaf
email templates, ADR 0013 i18n), §12 (non-additive schema change approval); PRD §6.9 FR-9.1–9.4,
§17 (channels, event list, delivery); ADR 0005 (system-scoped infrastructure), ADR 0003
(`runAsSystem` is not an RLS bypass), ADR 0013 (i18n)

---

## Context

Sub-phase 1.10 (`docs/CURRENT_PHASE.md`) had to turn six ad-hoc, hand-built-HTML transactional
emails (invite, password reset, leave requested/approved/rejected/cancelled) into a real templated
catalog, wire the four PRD §17.2 events that did not fire at all (holiday reminder, birthday, work
anniversary, document expiry), and give Admin `/admin/notifications` to inspect and retry failed
sends and to switch categories on and off (FR-9.3). Three decisions had to be made before any of
that could be planned; the user was consulted on all three (`AskUserQuestion` in this phase's plan
approval) and this ADR is their record.

### 1. What "document expiring" monitors

PRD §17.2 lists a "Document expiring (30/14/7 days before)" event, but `documents.EmployeeDocument`
has no expiry column and adding one would have dragged in both upload forms and a Documents-tab
column change for a feature the phase's own Complexity S / 0.5-week sizing did not budget for.
`people.GovernmentId.expiryDate` already exists, and PRD FR-3.7 already promises "Expiry monitored
for reminders" — exactly this event, already scoped, already stored.

**Decision:** the event reads `government_id.expiry_date`, not a new `employee_document` column.
The email carries the ID type, country, and date, and never `id_number` (encrypted at rest, ADR
0008) — an inbox is not somewhere to decrypt it into.

### 2. Where the four category switches live

No tenant-settings table or page existed anywhere in the app before this phase. Two shapes were
considered:

- **Four boolean columns on `tenant`**, alongside `weekend_days` and `timezone`, which already live
  there as tenant-wide configuration.
- **A new `notification_setting` table** — a tenant-scoped entity with `TenantAwareEntity` + RLS +
  its own ArchUnit isolation test, one row per (tenant, category), absence meaning default.

**Decision:** four columns on `tenant`. PRD §17.2 names a closed, small category list (holiday
reminder, birthday, work anniversary, document expiry — the leave lifecycle and invite/reset are
not "categories" a tenant can mute, they are the product working). A whole new table, entity,
repository, and isolation test for four booleans would be the kind of "extensible for later"
machinery CLAUDE.md §11 rules out ("writing generic wrappers 'in case we need it later'"). Birthday
and work anniversary default `FALSE`; PRD §17.2 marks both "opt-in per tenant" explicitly. Holiday
reminder and document expiry default `TRUE` — a forgotten holiday and an ID that lapsed unnoticed
are exactly the failures these reminders exist to prevent, and existing tenants should not silently
lose them on upgrade.

### 3. `tenant.primary_color`

`docs/CURRENT_PHASE.md`'s carried-forward items named this phase as the trigger to finally inject
`tenant.primary_color` into `--bs-primary` at layout render — a feature that had never actually
been built despite the column existing since sub-phase 1.1. Three problems surfaced once this
phase's branding-merge work made it the natural point to look:

- **The column was already dead.** A repo-wide grep found it referenced in exactly one place: the
  migration that created it. No Java, CSS, or Thymeleaf code read it.
- **Every row held a stale default.** The column's DB default was `#4f46e5` (Tailwind indigo-600),
  not the actual brand colour `#0F5568` (Caderly Petrol) that `theme-overrides.css` has used since
  the brand was set — including the first tenant's own seeded row.
- **No Admin editor existed to set it**, so building the injection now would have produced a
  feature with no way to reach a non-default value — dead code with extra steps.
- **`docs/design-system/guidelines/BRAND.source.md` §9.3 change #1** independently recommends
  dropping the column: *"One colour for everyone now. It becomes an application constant, not a
  column plus a form field plus a per-tenant email merge."*

**Decision, confirmed with the user:** drop `tenant.primary_color` entirely rather than build the
injection. Branding is one Caderly brand — petrol `#0F5568` — for every tenant, in both the app UI
(unchanged; `theme-overrides.css` already hardcodes it) and in email (`templates/email/_layout.html`
hardcodes the same hex, since mail clients do not support CSS custom properties). `tenant.logo_url`
stays: a tenant logo is a guest on the brand, not a replacement for it (BRAND.source.md §8.1), and
email templates use it when set, falling back to the wordmark exactly as the topbar does.

This is a **non-additive schema change** (`ALTER TABLE tenant DROP COLUMN primary_color`), which
CLAUDE.md §12 requires be called out rather than made silently. It is safe here because the column
carried no information any code was using — the migration comment for the column's own creation is
now the only place it is mentioned outside this ADR and the migration that removes it.

---

## Decision: one templated seam

Every email in the catalog — old and new — now goes through a single path:

1. `notifications.EmailEvent` names all ten PRD §17.2 events. Each carries a `templateKey`, which
   is the single source of three things that used to be independently chosen and could therefore
   disagree: the Thymeleaf template path (`templates/email/<key>.html`), the subject's
   `messages.properties` key (`email.<key>.subject`), and the outbox row's new `event_type` column.
2. `notifications.EmailTemplateService` renders the event's template with a plain Thymeleaf
   `Context` (not an `IWebContext` — rendering happens off the request thread for the four new
   scheduled events, so no `@{...}` link expressions are possible; URLs arrive as model attributes)
   against the app's existing `SpringTemplateEngine`. No second engine, no new dependency.
3. `notifications.EmailOutboxService.enqueue(EmailEvent, String toEmail, Map<String,Object> model,
   Object... subjectArgs)` replaces the old raw `enqueue(tenantId, subject, bodyHtml)` overload,
   which is now private. That is what makes "no inline email HTML anywhere outside a template" a
   structural fact instead of a review item.
4. Rendering happens at **enqueue time**, and the finished HTML is stored on the row exactly as
   before — `EmailDispatcher` and `EmailDelivery` are untouched. A template edit therefore never
   retroactively rewrites mail that was already decided on, which matters for the outbox's own
   at-least-once redelivery guarantee.

Two markup values in `messages.properties` (`email.leave-requested.body.message`,
`email.leave-cancelled.body.message`) previously embedded `<strong>{0}</strong>`, which would have
forced `th:utext` at the new template call sites and unescaped a user-supplied employee name
straight into the body. Both were rewritten as plain text; emphasis now belongs to the template.
`EmailTemplateRenderTest` asserts the escaping and that no template references an unresolved
message key (Thymeleaf renders those as `??key_locale??` rather than failing).

### The two new scheduled jobs

`notifications` may not depend on `people` or `timeoff` — both already depend on it, and
`ArchitectureTest.packages_haveNoCycles` would reject the edge back. `timeoff.HolidayReminderJob`
and `people.DailyReminderJob` therefore live in the modules that own the underlying dates and call
into `notifications`, exactly as `LeaveRequestService` already does for the leave lifecycle. Both
copy `people.EmployeeTerminationJob`'s shape (externalised cron, an `enabled` flag tests flip off,
a public method tests call at a known moment) including its reason for setting a **real** tenant
context per iteration rather than `TenantContext.runAsSystem`: `employee`, `government_id`, and
`public_holiday` are RLS-protected, and system mode is not an RLS bypass (ADR 0003).

Each recipient is skipped when the same event was already queued for them since the start of the
tenant's day (`EmailOutboxService.alreadyQueuedSince`, backed by `idx_email_outbox_dedupe`), so a
process restart across the cron minute cannot mail the whole company twice.

### The Admin delivery log's tenant boundary

`email_outbox` is system-scoped infrastructure with no `@TenantId` and no RLS policy (ADR 0005
decision B) — the table's own migration header says any Admin viewer over it "must filter by
tenant_id explicitly, because the ORM will not do it here." `notifications.NotificationAdminService`
is that filter: both `list` and `requeue` read `TenantContext.require()` directly rather than taking
a tenant id from their caller, so a controller cannot pass the wrong one. A row belonging to another
tenant is reported as `NotFoundException`, not `AccessDeniedException` — confirming a row is
forbidden would also confirm it exists. `NotificationAdminServiceTest` is written to fail if either
the tenant predicate or the not-found behavior is removed (verified by deliberately breaking each
and confirming the test catches it before merging).

The optional status/date-range filters use a `Specification`, not a `@Query` with
`(:param IS NULL OR ...)` guards — that pattern failed at runtime because Postgres cannot infer an
untyped bare parameter's type in `? IS NULL` and rejects the statement outright.

---

## Consequences

- `tenant.primary_color` is gone; a future "tenant branding" feature (if the product ever needs one)
  starts from zero, not from resurrecting this column's stale default.
- `docs/CURRENT_PHASE.md`'s carried-forward `--bs-primary` injection item is closed by removing the
  feature it described, not by building it.
- Email HTML no longer exists as Java string literals anywhere; `identity.IdentityEmails` and
  `timeoff.TimeoffEmails` are deleted, along with their byte-for-byte-duplicated `wrap()` chrome.
- A new `ArchitectureTest` rule confines `JavaMailSender` to `notifications.system` — CLAUDE.md §6a
  rule 1 was convention-only before this phase; it is now enforced by ArchUnit.
- The app's first paginated list (`/admin/notifications`) sets the pattern for future ones:
  `Page`/`Pageable` from Spring Data, prev/next-only navigation (UI_Guidelines §6), and a
  `.pagination` component-local CSS override (Bootstrap 5.3 compiles `$primary` into
  `--bs-pagination-*` at build time — the same gotcha `docs/LEARNINGS.log`'s 2026-08-17 entry
  already documented for buttons and nav-pills).
- **`documents.EmployeeDocument` still has no expiry tracking.** If a future requirement needs
  expiry on uploaded files specifically (not government IDs), that is new scope, not something this
  phase's document-expiry event silently covers.
- **Admin cancelling *someone else's* leave still notifies nobody** — an existing gap noted but not
  fixed in this phase (see `docs/CURRENT_PHASE.md`'s carried-forward items); PRD §17.2 only
  specifies "cancelled *by employee* → approver," so the asymmetry is in scope, not a regression.

## Alternatives considered

**1. Add `employee_document.expires_on` for the document-expiry event, ignoring `government_id`.**
Rejected: `employee_document` has no facade for cross-module reads today, so this would also have
required standing one up; `government_id.expiry_date` already exists, is already tenant-scoped, and
FR-3.7 already promises exactly this reminder.

**2. A `notification_setting` table instead of four `tenant` columns.** Rejected per decision 2
above — more extensible, but CLAUDE.md §11's YAGNI principle argues against building generic
machinery for four fixed, closed-list booleans.

**3. Keep `tenant.primary_color` and finally wire the `<style>` injection this phase, as
`CURRENT_PHASE.md` originally asked.** Rejected after the stale-default and no-editor problems
surfaced (decision 3 above); building an injection with no way to reach a non-default value would
have shipped dead code with extra steps, and the column's own migration-era history (`#2563EB` →
`#4f46e5`, neither the real brand colour) suggested this was already drifting rather than converging.

**4. A `@Query` with `(:status IS NULL OR ...)` guards for the Admin log's optional filters**, matching
the style already used elsewhere in the app (e.g. `holidays.findAllByOrderByDateAsc` callers filtering
in memory). Rejected after it failed at runtime with `could not determine data type of parameter` —
Postgres needs a typed comparison to infer a bind parameter's type, and a bare `?` compared only to
`NULL` gives it nothing to infer from. `JpaSpecificationExecutor` was the fix.

## References

- `notifications.EmailEvent`, `NotificationCategory`, `EmailTemplateService`, `EmailOutboxService`
- `notifications.NotificationAdminService`, `web.AdminNotificationsController`,
  `templates/admin/notifications.html`
- `templates/email/_layout.html`, `_components.html`, and the ten event templates
- `timeoff.HolidayReminderJob`/`HolidayReminderService`, `people.DailyReminderJob`/`ReminderService`
- `tenant.Tenant` (notification-setting columns, `primaryColor` removed), `tenant.TenantFacade`
  (`currentBranding`, `currentNotificationSettings`, `updateNotificationSettings`)
- `src/main/resources/db/migration/V202609151000__notification_events_and_settings.sql`
- `docs/design-system/guidelines/BRAND.source.md` §8.1 (tenant logo as guest), §8.2 (transactional
  email voice), §9.3 change #1 (drop `primary_color`)
- `docs/LEARNINGS.log`, 2026-08-17 entries (Bootstrap 5.3 component-local-var gotcha; the stale
  `primary_color` default caught by a repo-wide grep)
- `docs/Caderly_PRD.md` §6.9 FR-9.1–9.4, §17.1–17.3
