# Caderly — Product Requirements Document (PRD)

**Version:** 1.0
**Status:** Draft for Review
**Author:** Hasan Kagalwala (with product/architecture assist)
**Date:** 2026-07-20
**Owner:** Hasan Kagalwala

---

## 1. Executive Summary

Caderly is a multi-tenant HRIS and Leave Management SaaS. It replaces a first customer's current use of TalentHR's free plan, then extends into a broader HR platform (performance reviews, onboarding checklists, deeper reporting).

The MVP achieves feature parity with the TalentHR free plan for daily use: employee directory, employee profile, leave booking and approval, team calendar, company and personal documents, task/approval inbox, admin management of the tenant. It is designed from day one as a generic SaaS product: every company (tenant) is isolated by `tenant_id`, gets its own subdomain and branding, and configures its own leave policies, org units, and holiday calendar.

The stack is a modular monolith on **Java 25 + Spring Boot 4.1**, server-rendered UI (**Thymeleaf + htmx + Alpine.js + Bootstrap 5**), and **PostgreSQL 17**, deployable as a Docker container to a Debian VPS (for the first customer) or any cloud (for future customers). Development is done and maintained by a single engineer (the author), so developer productivity, familiarity, and simplicity are first-class concerns; unnecessary complexity is a design smell.

---

## 2. Product Vision

To be the HR system a small-to-mid-sized company actually enjoys using — fast, clean, honest about the rules it enforces, and configurable enough that any company can adopt it without asking us to change code. In three years, Caderly becomes the operating system for the people side of an SMB: hiring, performance, learning, and light payroll integration, all with a single sign-on, a single directory, and a single audit trail.

---

## 3. Goals and Non-Goals

### 3.1 Goals (MVP, ~3-4 months)

- Replace TalentHR free plan for the first customer's 8-10 person team without regression in daily use.
- Fully generic multi-tenant SaaS: onboard a new tenant with 1 SQL row + 1 admin invite, no code changes.
- Employee self-service: profile, leave booking, documents, calendar of teammates.
- Manager tooling: approve leave, see team calendar, view direct reports.
- Admin tooling: manage employees, org units (Division/Department), leave types & balances, holiday calendar, files, audit log, reports.
- Configurable leave policy per tenant with tenant-specific holidays and weekends.
- Email notifications for lifecycle events.
- Baseline security: HTTPS everywhere, tenant isolation enforced in code + DB, encryption at rest, full audit log.
- Deployable to a Debian VPS (first customer) or any Docker-capable host in <10 minutes.

### 3.2 Goals (12-24 months)

- Onboarding/offboarding checklist templates.
- Performance reviews (goals/OKRs, 1:1s, 360 feedback).
- Public REST API + tenant API keys.
- Slack/MS Teams webhook notifications.
- Custom report builder.
- Arabic + RTL support.
- SOC2-ready controls (formal audit only when a paying customer requires it).

### 3.3 Non-Goals (explicitly out of scope for v1)

- Payroll calculation and payslip generation.
- Attendance / clock-in-out / shift management.
- Applicant Tracking System (ATS).
- Learning Management System (LMS).
- Self-serve billing (paid plans, Stripe). The public site and free self-serve sign-up moved into
  Phase 1 (Implementation Plan 1.15, ADR 0022); v1 is free to use, capped at 50 employees per
  self-serve workspace.
- Custom domains per tenant (subdomains only in MVP).
- Native mobile app (mobile-responsive web is enough).
- Multi-currency payroll.
- Real-time chat / messaging.
- SSO (Google/Microsoft/Apple/SAML) — deferred to Phase 2/3.

---

## 4. Personas

**Priya — Employee (30, Software Engineer at a Dubai company).**
Wants to book leave in under a minute, see who's out this week, upload her renewed Emirates ID, and never think about HR software again. Uses mobile web on her phone from a cafe.

**Rahul — Manager (40, Engineering Manager, 6 direct reports).**
Approves leave, checks team's PTO balance before planning a sprint, reviews his team's calendar for the next month. Doesn't want to log in unless there's something to act on — expects email nudges.

**Aisha — HR Admin (35, People Operations Lead).**
Adds new hires, sets leave balances, uploads the employee handbook, generates a leave utilization report at end of quarter, sees the audit log if something looks off. Cares deeply about accuracy and audit-ability.

**Marco — Super Admin (Hasan / Caderly operator).**
Onboards new tenants, configures their subdomain, invites their first Admin, monitors health, resets things when they break. Not visible in the tenant's UI.

**External stakeholder — Anita, CFO of a future customer.**
Never uses the tool. Needs assurance that data is encrypted, tenant-isolated, and that termination revokes access instantly.

---

## 5. User Roles

Three fixed roles per tenant + one cross-tenant role.

| Role | Scope | Typical actions |
|---|---|---|
| **Employee** | Self only | View & edit own profile (limited fields), upload own documents, book/cancel own leave, view team calendar, view company files, mark own tasks done. |
| **Manager** | Self + direct reports (transitively, N levels down) | Everything an Employee does + approve/reject direct reports' leave, view reports' profiles (read-only), view reports' leave history & balances. |
| **Admin** | Entire tenant | Everything above + CRUD employees/departments/divisions/leave types/holidays/company files, view audit log, generate reports, override any leave decision, configure tenant settings, invite users. |
| **Super Admin (Caderly staff)** | All tenants | Cross-tenant. Create/suspend tenants, provision first Admin, view system health. Never appears in tenant's UI. |

An **Admin is also always an Employee** of their tenant (has a profile, can book leave). A **Manager is also an Employee**. Role escalation is additive.

Managers are derived: user X is a "manager" if any other user has `manager_id = X.id`.

---

## 6. Functional Requirements

Grouped by module. Detailed acceptance criteria in §10.

### 6.1 Identity & Access
- FR-1.1 Users log in with email + password.
- FR-1.2 Passwords hashed with BCrypt (cost 12), stored per tenant.
- FR-1.3 Password reset via time-limited email token (24h, one-time use).
- FR-1.4 First-login flow: user receives invite email → sets password → accepts.
- FR-1.5 Optional TOTP 2FA (Google Authenticator compatible), enforceable per-tenant for Admin role.
- FR-1.6 Session timeout: 8 hours idle (configurable per tenant).
- FR-1.7 Account lockout: 5 failed attempts → 15 min lock.
- FR-1.8 Super Admin can create tenants and initial Admin user.

### 6.2 Multi-Tenancy
- FR-2.1 Every table (except `tenant`, `super_admin`, `system_config`) has `tenant_id` NOT NULL.
- FR-2.2 Tenant resolved from subdomain (`{tenant_slug}.caderly.app`) at request entry.
- FR-2.3 All persistence auto-filtered by current tenant.
- FR-2.4 Cross-tenant reads/writes are impossible from tenant-scoped endpoints (enforced by test suite).
- FR-2.5 Tenant has slug, name, logo, timezone, locale, weekend definition (which days of week), fiscal year (fixed to Jan-Dec for MVP). No per-tenant primary color — one shared Caderly brand color for every tenant (ADR 0016, sub-phase 1.10).

### 6.3 Employee Management (Employee Profile)
- FR-3.1 Employee record fields: first name*, last name*, email* (unique per tenant), phone, employee code, birth date, gender, marital status, nationality, citizenship, address, city, country, postal code, profile photo, hire date, employment type (Full-Time / Part-Time / Contract / Intern), termination date (nullable), status (Active / On Leave / Terminated / Invited).
- FR-3.2 Job info: department (FK), division (derived), job title, work location, working hours per day, currency, base compensation (visible to Admin only), bonus (Admin only), benefits (list).
- FR-3.3 Employment status history: append-only log of status changes with effective date.
- FR-3.4 Manager history: append-only log of manager assignments.
- FR-3.5 Education: institution, degree, field, start/end year (multiple entries).
- FR-3.6 Emergency contacts: name, relationship, phone, email (multiple entries).
- FR-3.7 Government IDs: type (Passport / National ID / Visa / Emirates ID / Other), number, country, issue date, expiry date. Multiple per employee. Expiry monitored for reminders.
- FR-3.8 Bank details: bank name, account name, account number, IBAN, SWIFT/BIC. Encrypted at rest (column-level). Visible only to Admin + owning employee.
- FR-3.9 Documents: upload files (PDF, images, doc) tagged as employee-private (visible to employee + Admin) or admin-only (visible to Admin only).
- FR-3.10 Employee can edit their own contact details, address, emergency contacts, upload documents. Fields like hire date, department, job title, compensation are Admin-only.

### 6.4 Organization Management
- FR-4.1 Tenant has a hierarchy: **Division → Department**. Each Department belongs to exactly one Division.
- FR-4.2 Admin CRUD on Divisions and Departments (soft-delete only if unused; block if any active employee is in it).
- FR-4.3 Every employee belongs to exactly one Department at any time (nullable during Invited state).
- FR-4.4 Manager assignment is a separate field on employee (`manager_id`), not derived from department.

### 6.5 Leave Management
- FR-5.1 Admin defines Leave Types per tenant: name, icon, color, paid flag, requires-approval flag, allow-half-day flag, description.
- FR-5.2 Admin sets annual balance per leave type per employee (or per employment-type template). Balance granted on Jan 1; on hire mid-year, pro-rated to remaining months (configurable, default: pro-rated).
- FR-5.3 Employee books leave: select leave type, start date, end date, half-day (AM/PM) flag on start/end, note.
- FR-5.4 System calculates duration in working days: exclude weekends (per tenant weekend config) and public holidays (per tenant calendar).
- FR-5.5 Booking requires current balance ≥ duration; otherwise rejected client-side with clear message. No negative balance.
- FR-5.6 Booking status flow: PENDING → APPROVED / REJECTED / CANCELLED. Only PENDING deducts against future-balance display (soft hold).
- FR-5.7 Approver = booking employee's manager. If no manager, Admin. Admin can approve/reject anything.
- FR-5.8 Employee can cancel own request if PENDING or if APPROVED but start date ≥ today+1 (configurable).
- FR-5.9 No carry-over. On Dec 31, unused balance is lost. No encashment record required.
- FR-5.10 Public Holidays: Admin uploads per-tenant list (name, date, optional location filter for future). Auto-counted as non-working days.
- FR-5.11 Weekend: Admin sets weekend days per tenant (default Sat+Sun; UAE default Fri+Sat historically but Sat+Sun is now standard).
- FR-5.12 Book-time-off widget accessible from every page (top bar CTA).

### 6.6 Team Calendar
- FR-6.1 Grid view: rows = employees (filterable), columns = days. Each cell shows leave (colored bar with type icon).
- FR-6.2 Filter by department, division, leave type, date range.
- FR-6.3 Hover tooltip: leave type, duration ("3 working days"), dates.
- FR-6.4 Managers see their reports by default; Admin sees everyone.
- FR-6.5 iCal feed URL per user (unique, tokenized) → subscribe in Google Calendar / Outlook. Content: own leave + optionally team's leave.

### 6.7 Files
- FR-7.1 Company Files: uploaded by Admin, visible to everyone in tenant. Name, size, uploaded-by, uploaded-at, download.
- FR-7.2 Employee Documents: on employee profile, per §6.3.9.
- FR-7.3 File size limit: 25 MB per file (configurable).
- FR-7.4 Allowed types: PDF, PNG, JPG, DOCX, XLSX, PPTX. Block executables.
- FR-7.5 Storage backend abstraction: local filesystem or S3-compatible. Configured at deployment.

### 6.8 Tasks & Action Inbox
- FR-8.1 "For Action" page shows Tasks + Pending Time-off Requests for the current user.
- FR-8.2 Manager sees their reports' pending leave requests here.
- FR-8.3 Admin sees org-wide pending leave requests here.
- FR-8.4 Tasks in MVP are limited to system-generated (e.g., "Complete your profile") and manually assigned by Admin. Full checklist templates in Phase 2.

### 6.9 Notifications (Email)
- FR-9.1 Templated transactional emails, per-tenant branded (logo; one shared Caderly primary color for every tenant — ADR 0016).
- FR-9.2 Events: invite user, password reset, leave requested (to approver), leave approved/rejected (to employee), leave cancelled (to approver), holiday reminder (day-before to whole tenant), birthday & work-anniversary (to team, per config), document expiry (30/14/7 days before, to employee + Admin).
- FR-9.3 Admin can disable specific notification categories per tenant.
- FR-9.4 Per-user "digest vs. immediate" preference (Phase 2).

### 6.10 Reporting
- FR-10.1 Leave Balance Report: for each active employee, remaining balance per leave type. Filterable by department. CSV export.
- FR-10.2 Leave Utilization Report: per employee, per type, used in date range. Bar chart + table. CSV export.
- FR-10.3 Headcount Report: active employees over time by department. Line chart. CSV export.
- FR-10.4 Absence Calendar Export: month PDF/CSV.
- FR-10.5 Custom Report Builder (Phase 2): choose entity (Employee / Leave Request), columns, filters, group-by, output as table + CSV.

### 6.11 Audit Log
- FR-11.1 Every write operation (create/update/delete) recorded: timestamp (UTC), tenant_id, actor user_id, actor role, entity type, entity id, action, before JSON, after JSON, IP address, user agent.
- FR-11.2 Login events (success/failure): timestamp, tenant_id, user_id (if known), IP, user agent, reason (for failure).
- FR-11.3 Admin can browse audit log with filters (user, entity type, date range, action).
- FR-11.4 Audit log is append-only; no UI to delete. Retention: 7 years (configurable).
- FR-11.5 Audit log itself is not audited (avoid infinite recursion).

### 6.12 Tenant Administration (Super Admin)
- FR-12.1 Super Admin console at `admin.caderly.app` (or `/superadmin` path guarded by IP allowlist).
- FR-12.2 Create tenant: slug (URL-safe, unique), display name, logo, timezone, weekend days, first Admin email. No primary color field — ADR 0016 made brand color a fixed application constant, not a per-tenant setting.
- FR-12.3 Suspend tenant (soft): all users blocked from login until unsuspended.
- FR-12.4 Delete tenant (hard, with 30-day grace period).
- FR-12.5 Impersonate as Admin (with explicit audit log entry) for support.

---

## 7. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Performance** | p95 page load < 400 ms for typical pages (profile, calendar, dashboard) at 50 employees × 50 tenants baseline. p95 API JSON responses < 200 ms. |
| **Availability** | 99.5% monthly (allows ~3.6 h/mo downtime) for self-hosted single-instance. Higher tiers require HA setup, not part of MVP. |
| **Scalability** | Single Spring Boot instance + single Postgres must serve baseline (50×50). Design must not preclude horizontal scale later: application code is stateless *except* for the HTTP session, which is deliberately in-JVM in MVP and moves to a shared store (`spring-session-jdbc` → Postgres) as a config-only change when we add a second instance. |
| **Security** | See §19. |
| **Data integrity** | All writes transactional; leave-booking is optimistic-locked to prevent double-book of same day. |
| **Compliance readiness** | Design controls for future SOC2 Type II and GDPR compliance. No formal audit in MVP. |
| **Backup** | Daily Postgres logical backup (pg_dump) retained 30 days locally + optional off-site. Files backup follows storage backend policy (S3 versioning). |
| **Portability** | Runs on Debian 12+ VPS with Docker, or on any cloud with Docker/Kubernetes. No cloud-specific SDK dependencies. |
| **Observability** | Actuator health, Prometheus metrics endpoint, structured JSON logs to stdout. |
| **Maintainability** | Modular monolith with ArchUnit tests enforcing module boundaries. Static analysis: Checkstyle + SpotBugs + PMD in CI. |
| **Test coverage** | Unit ≥ 70% line, integration on every use-case, at least one E2E happy-path per module. |
| **Accessibility** | WCAG 2.1 AA target. Semantic HTML, ARIA labels on interactive components, keyboard-navigable. |
| **Browser support** | Latest 2 stable versions of Chrome, Firefox, Safari, Edge. Mobile Safari + Chrome on mobile. |
| **Internationalization** | All UI strings externalized (messages.properties). English only for MVP. Multi-timezone from day 1. |

---

## 8. Feature List (High-Level, prioritized)

**MVP (Phase 1):**
1. Multi-tenant login (email/password) + tenant subdomain resolution
2. Tenant provisioning (Super Admin console)
3. Employee CRUD (Admin) + self-service profile (Employee)
4. Division & Department CRUD
5. Employee documents (upload/download/delete)
6. Company files (upload/download/delete)
7. Leave types configuration (Admin)
8. Public holidays configuration (Admin)
9. Weekend config per tenant
10. Annual leave balance grant (Jan 1 job + on-hire pro-rate)
11. Leave request + approval flow (Employee → Manager → Admin fallback)
12. Team calendar (grid view + filters)
13. Home dashboard (widgets: book time off, my peers, time off today, upcoming holidays)
14. For Action inbox (pending approvals + tasks)
15. Email notifications (invite, reset, leave lifecycle, document expiry)
16. Audit log (write + login events, admin view)
17. Leave balance & utilization reports (CSV export)
18. Headcount report
19. iCal calendar feed (self)
20. Public site + free self-serve workspace sign-up with email verification (ADR 0022)
21. Docker deploy + Debian install guide

**Phase 2:**
- TOTP MFA (mandatory for Admin)
- Onboarding/offboarding checklist templates
- Slack/MS Teams webhook notifications
- Custom report builder
- Document expiry reminders (already scheduled in MVP, richer UI here)
- Google/Microsoft SSO
- Team calendar in iCal
- Public REST API + per-tenant API keys
- Arabic + RTL

**Phase 3:**
- Performance reviews (OKRs, 1:1s, 360 feedback)
- Custom domains per tenant
- SAML SSO
- Attendance tracking (basic clock in/out)
- Payroll CSV export presets per country

**Phase 4:**
- Recruitment / ATS module
- Learning Management module
- Self-serve billing (Stripe) on top of the Phase 1 public site
- Native mobile app

---

## 9. Detailed User Stories

Format: **US-<module>.<n>** — As a **role**, I want **goal**, so that **benefit**. Acceptance criteria in §10 use the same ID.

### 9.1 Identity
- **US-ID.1** As an *invited user*, I want to click the invite link in my email and set my password, so that I can start using Caderly.
- **US-ID.2** As a *user*, I want to reset my password via email, so that I recover access when I forget it.
- **US-ID.3** As an *Admin*, I want to require MFA for all Admins in my tenant, so that privileged accounts are hardened.
- **US-ID.4** As a *user*, I want the system to log me out after inactivity, so that a shared laptop is safe.

### 9.2 Employee Profile
- **US-EMP.1** As an *Employee*, I want to view and edit my personal information (contact, address, emergency contacts), so that HR always has current details.
- **US-EMP.2** As an *Employee*, I want to upload my ID documents with expiry dates, so that HR is alerted before they expire.
- **US-EMP.3** As an *Admin*, I want to create a new employee record and send them an invite, so that they can self-onboard.
- **US-EMP.4** As an *Admin*, I want to change an employee's department, manager, employment status, and see history, so that the org record is accurate over time.
- **US-EMP.5** As a *Manager*, I want to view my direct reports' profiles (read-only), so that I know their contact info and manager history.
- **US-EMP.6** As an *Admin*, I want to terminate an employee (set termination date + revoke login), so that offboarded staff cannot access data.

### 9.3 Leave
- **US-LV.1** As an *Employee*, I want to book time off in under 30 seconds from any page, so that I don't waste time on paperwork.
- **US-LV.2** As an *Employee*, I want to see my remaining leave balance per type before booking, so that I don't over-request.
- **US-LV.3** As an *Employee*, I want to cancel a pending or upcoming approved leave request, so that plans changing doesn't require asking HR.
- **US-LV.4** As a *Manager*, I want an email + inbox notification when a report requests leave, so that I can approve promptly.
- **US-LV.5** As a *Manager*, I want to approve/reject with an optional note, so that the employee knows why.
- **US-LV.6** As an *Admin*, I want to define custom leave types (Vacation, Sick, Bereavement, Unpaid, Marriage, etc.) with icons and colors, so that our policy is represented.
- **US-LV.7** As an *Admin*, I want to set the annual balance per leave type per employment type, so that new hires get the right allowance automatically.
- **US-LV.8** As an *Admin*, I want to upload our public holiday calendar for the year, so that leave duration is calculated correctly.
- **US-LV.9** As an *Admin*, I want to override any leave decision (approve, reject, cancel, edit dates), so that edge cases are resolvable.

### 9.4 Calendar
- **US-CAL.1** As an *Employee*, I want to see who on my team is out this week, so that I know who to escalate to.
- **US-CAL.2** As a *Manager*, I want to see my team's leave in a month grid, so that I can plan sprints.
- **US-CAL.3** As an *Employee*, I want to subscribe to my personal calendar feed in Google Calendar, so that my leave appears in my planner automatically.

### 9.5 Files
- **US-F.1** As an *Admin*, I want to upload the company handbook to the Files section, so that everyone can download it.
- **US-F.2** As an *Employee*, I want to upload my signed contract to my profile, so that HR has a copy.

### 9.6 Reporting
- **US-RPT.1** As an *Admin*, I want to export the current leave balance for all employees to CSV, so that I can share it with Finance.
- **US-RPT.2** As an *Admin*, I want a report of Sick Leave utilization by department last quarter, so that I can spot patterns.

### 9.7 Audit
- **US-AUD.1** As an *Admin*, I want to see who changed an employee's compensation and when, so that I can investigate.
- **US-AUD.2** As an *Admin*, I want to see failed login attempts, so that I can spot brute-force attempts.

### 9.8 Super Admin
- **US-SUP.1** As a *Super Admin*, I want to provision a new tenant with 1 form, so that onboarding is fast.
- **US-SUP.2** As a *Super Admin*, I want to suspend a tenant, so that non-payment or breach can be enforced.

---

## 10. Acceptance Criteria (selected — full set in test plan)

**AC-LV.1 (Book leave):**
- Given I am logged in and have a Vacation balance of 10 days
- When I click "Book time off", pick Vacation, start = Mon 2026-08-03, end = Wed 2026-08-05, no half-days, note = "trip"
- Then a leave request is created with status PENDING, duration = 3 working days, submitted to my manager
- And my displayed "future balance" for Vacation drops to 7
- And my manager receives an email within 60s

**AC-LV.2 (Weekend & holiday exclusion):**
- Given tenant weekend = Sat/Sun and public holiday Fri 2026-08-07
- When I book Mon 2026-08-03 to Mon 2026-08-10
- Then duration = 5 working days (Mon-Thu are 4 working, Fri is holiday, Sat/Sun weekend, Mon = 1 → total 5)

**AC-LV.3 (Insufficient balance):**
- Given my Vacation balance = 2 days
- When I try to book 3 days
- Then request is rejected client-side with "Insufficient balance (2 remaining, 3 requested)"
- And no leave request is persisted

**AC-LV.4 (Half day):**
- Given I book Mon start half-day AM = false, half-day PM = true, end Wed half-day AM = true
- Then duration = 2.0 (Mon full + Tue full + Wed half? actually Mon 0.5 + Tue 1 + Wed 0.5 = 2.0) — recompute per exact rule: half-day PM on start = 0.5, half-day AM on end = 0.5, middle full → 0.5+1.0+0.5 = 2.0 ✅

**AC-EMP.1 (Create employee):**
- Given I am Admin
- When I POST /employees with valid data
- Then employee is created with status = INVITED, invite email queued, no login yet
- When invitee clicks link and sets password
- Then employee status = ACTIVE and login succeeds

**AC-EMP.2 (Terminate):**
- Given employee X is Active
- When Admin sets termination_date = today
- Then X.status = TERMINATED, login blocked, active leave requests > termination_date are cancelled with note "Employee terminated"

**AC-TENANT.1 (Isolation):**
- Given tenant A has employee "Priya" and tenant B has employee "Rahul"
- When a user of tenant A queries /employees
- Then only Priya returns, and no SQL query in the audit log references tenant B

**AC-AUDIT.1 (Compensation change):**
- Given Admin edits employee compensation from 10000 to 12000
- Then audit log has entry with actor, timestamp, entity=Employee, action=UPDATE, before JSON containing {compensation:10000}, after JSON containing {compensation:12000}

**AC-CALENDAR.1 (iCal feed):**
- Given I open my Settings → Calendar integration
- When I copy the iCal URL and subscribe in Google Calendar
- Then within 24h Google Calendar shows all my APPROVED leaves as all-day events

---

## 11. Business Rules

**BR-1 Tenant isolation.** No tenant may read or write another tenant's data. Enforced by tenant filter in ORM + Postgres RLS + tests.

**BR-2 Manager transitivity.** For approvals, the direct manager approves. If the direct manager is out (marked "delegated") or missing, escalate to their manager, then Admin. MVP: only direct manager + Admin fallback. Delegation in Phase 2.

**BR-3 Balance immutability.** Balances are computed as `granted - used`. `used` = sum of APPROVED leave requests intersecting the current year. PENDING requests do NOT deduct from `remaining`, but the UI shows "future balance" = `remaining - sum(pending)` for the employee's own view.

**BR-4 No negative balance.** Booking rejected if `pending + requested > remaining`.

**BR-5 No carry-over.** On Dec 31 23:59 UTC (per tenant timezone), a snapshot of the year's usage is stored; on Jan 1 00:00, balances are re-granted per configured allowance. Unused days are lost.

**BR-6 Self-approval forbidden.** An employee cannot approve their own leave, even if they are also a Manager or Admin (Admin override still requires audit reason).

**BR-7 Duration in working days.** Sum of days between start and end (inclusive), minus weekends (per tenant), minus public holidays (per tenant), applying half-day adjustments.

**BR-8 Booking window.** Employees may book leave from today to today + 12 months. Historical booking (start_date < today) allowed only for Sick Leave (or leave types flagged `allowsBackdated=true`).

**BR-9 Cancellation.** PENDING cancellable anytime. APPROVED cancellable if start_date ≥ today + 1. Otherwise requires Admin.

**BR-10 Employee immutability by self.** Employees cannot edit hire_date, employment_status, department, manager, job_title, compensation, or bank_details of themselves via self-service (Admin only).

**BR-11 Termination cleanup.** On termination: revoke sessions, block login, cancel future leave requests, keep audit + records for retention period. Employee is soft-hidden from People directory (visible in Terminated filter).

**BR-12 Email uniqueness.** Email is unique per tenant, not globally. Two tenants may have same email address.

**BR-13 Slug immutability.** Tenant slug cannot change after creation (breaks bookmarks + email links).

**BR-14 Document types.** Only whitelisted MIME types accepted (PDF, PNG, JPG, DOCX, XLSX, PPTX). File extension AND magic-byte check.

**BR-15 No overlapping leave.** An employee cannot have two leave requests (any leave type) with overlapping date ranges while both are PENDING or APPROVED. Booking is rejected outright; the employee must wait for a decision on, or cancel, the conflicting request first.

---

## 12. Leave Management Rules — Detailed

### 12.1 Data model recap
- **LeaveType**: `id, tenant_id, name, icon, color, paid_boolean, allows_half_day, allows_backdated, requires_approval, default_annual_allowance, description, active`
- **LeavePolicy** (optional Phase 2): `id, tenant_id, leave_type_id, employment_type, annual_allowance` — overrides default per employment type.
- **LeaveBalance**: `id, tenant_id, employee_id, leave_type_id, year, granted (decimal), used (decimal)`. Refreshed on grant events.
- **LeaveRequest**: `id, tenant_id, employee_id, leave_type_id, start_date, end_date, start_half_day_pm, end_half_day_am, duration_days (decimal), status, note, submitted_at, decided_at, decider_id, decision_note`
- **PublicHoliday**: `id, tenant_id, date, name`
- **TenantConfig.weekend_days**: `Set<DayOfWeek>` (bitmap or serialized).

### 12.2 Grant rules
- **New tenant setup:** Admin creates leave types, sets annual allowance per type. Initial grant on activation for current year, pro-rated by months remaining.
- **New hire:** Balance = `annual_allowance * (months_remaining_in_year / 12)`, rounded to nearest 0.5. E.g., hired July 1 with 30-day annual → 15 days for that year.
- **Annual grant job:** Runs Jan 1 00:00 in tenant timezone. Creates fresh `LeaveBalance` row per (employee, leave_type, year). Emits audit entry.
- **Manual adjustment:** Admin can adjust `granted` on any employee's balance with a required reason. Recorded in audit.

### 12.3 Duration algorithm (pseudocode)
```
function computeDuration(start, end, startHalfPM, endHalfAM, weekend, holidays):
  days = 0.0
  for d in dateRange(start, end):
    if d in weekend or d in holidays: continue
    if d == start and d == end:
      // same-day
      if startHalfPM and endHalfAM: raise "invalid: half-day both AM and PM on same day"
      if startHalfPM: days += 0.5
      else if endHalfAM: days += 0.5
      else: days += 1.0
    else if d == start and startHalfPM: days += 0.5
    else if d == end and endHalfAM: days += 0.5
    else: days += 1.0
  return days
```

### 12.4 Approval flow
1. Employee submits → status PENDING, `submitted_at = now`.
2. System identifies approver = employee.manager_id; if null → Admin(s) of tenant.
3. Approver receives email + "For action" inbox entry.
4. Approver clicks Approve/Reject with optional note → status APPROVED/REJECTED, `decided_at = now`, `decider_id = approver.id`.
5. Approved: `LeaveBalance.used += duration`. Rejected: no balance change.
6. Employee receives result email.
7. If auto-cancel (termination), status = CANCELLED with system note.

### 12.5 Configurability per tenant
- Leave types (unlimited).
- Annual allowance per type.
- Half-day allowed per type.
- Backdated booking allowed per type.
- Booking window (default 12 months forward).
- Weekend days.
- Public holidays (upload CSV or add one-by-one).

### 12.6 What we're NOT doing (explicit)
- No accrual (2.5/month). Full grant Jan 1.
- No carry-over.
- No encashment.
- No hourly leave.
- No compensatory off (accrual for weekend work). Phase 2+.
- No leave forfeiture on transfer (single tenant scope).

---

## 13. Organization Management

### 13.1 Structure
Two-level: **Division → Department**. Every Department has exactly one Division. Every active Employee has exactly one Department. Divisions and Departments are per-tenant.

### 13.2 Operations
- Admin creates Division (name, description).
- Admin creates Department (name, division_id, description, head_employee_id nullable).
- Rename allowed (audit-logged).
- Delete allowed only if no active employees and no historical assignments; otherwise soft-delete (marked `archived`).
- Reassign employees: bulk action to move employees from Dept A to Dept B.

### 13.3 Display
- People list shows Division + Department columns.
- Org chart view (Phase 2) renders tree of Divisions → Departments → Employees.

### 13.4 Migration path to N-level
Design the table with `parent_id` self-referencing on a unified `org_unit` table if we anticipate deeper hierarchies. For MVP, keep it as two explicit tables for clarity, but do NOT expose deeper API — makes future migration cleaner.

---

## 14. Employee Management — Detailed

### 14.1 Lifecycle states
`INVITED → ACTIVE → (ON_LEAVE) → TERMINATED`. `ON_LEAVE` is derived from having an active APPROVED leave that spans today.

### 14.2 Create employee (Admin flow)
1. Admin clicks "Add Employee".
2. Form: first name, last name, email, phone, hire date, employment type, department, job title, manager, working hours/day, currency, base compensation (optional).
3. On save: employee row created with status INVITED. Invite email sent with a one-time link (24h TTL). No password until invitee sets one.
4. Invitee accepts → sets password → status ACTIVE.

### 14.3 Edit employee
- Employee: can edit personal info, contact, address, emergency contacts, photo, upload documents.
- Manager: read-only on reports.
- Admin: everything, including job/compensation/employment status changes. History tables append.

### 14.4 Terminate
- Admin sets termination_date (past, today, or future).
- If today or past: status → TERMINATED immediately, session revoked, login blocked, future leave requests auto-cancelled.
- If future: scheduled job runs at midnight of termination date to apply above.
- Employee record retained (never deleted) — hidden from default People list, visible under filter "Terminated".

### 14.5 Custom fields (Phase 2)
Design DB with `employee_custom_field_definition` and `employee_custom_field_value` tables from day 1, but only expose UI in Phase 2. Avoids expensive migration later.

---

## 15. Department Management

Covered in §13. Additional detail:

- Department has one head (optional, chosen from active employees in that department).
- Department has a description (markdown, sanitized).
- Filter: "employees in this department" available on department detail page.
- API: `GET /divisions`, `GET /divisions/{id}/departments`, `POST/PATCH/DELETE /departments`.

---

## 16. Reporting

### 16.1 MVP reports

**Leave Balance (current year snapshot):**
- Columns: Employee, Department, Leave Type, Granted, Used, Remaining.
- Filters: department, division, leave type, employee status.
- Export: CSV, PDF (Phase 2).

**Leave Utilization (date range):**
- Columns: Employee, Leave Type, Days Used, Requests Count.
- Grouping: by Department, by Leave Type.
- Chart: stacked bar per department.
- Export: CSV.

**Headcount:**
- Chart: line of active employees over time (monthly).
- Filters: department, employment type.
- Export: CSV.

**Absence Calendar Export:**
- Month view PDF: rows = employees, columns = days, cell colored per leave type.
- CSV alternative for pipeline into other tools.

### 16.2 Phase 2: Custom Report Builder
- Entity picker: Employee, Leave Request.
- Column picker (multi-select).
- Filter builder (AND / OR groups, per-column operators).
- Group by + aggregate (count, sum, avg).
- Preview + save as named report + share within tenant (Admin only).

### 16.3 Implementation
- Reports run against read-only replicated view names to keep ORM out of the hot path? — Over-engineered for MVP. Use JPQL/JPA specifications + native SQL for aggregations. Cache results 60s.

---

## 17. Notifications

### 17.1 Channels (MVP)
- **Email** only, via SMTP.
- Templates in Thymeleaf, per-tenant branding (logo merged into HTML; primary color is a fixed
  application constant shared by every tenant, not per-tenant — ADR 0016, sub-phase 1.10).
- SendGrid / Postmark / SES / any SMTP provider — configured per deployment.

### 17.2 Events
| Event | Recipients | Subject template |
|---|---|---|
| User invited | Invitee | "Welcome to {tenant} — set your password" |
| Password reset | User | "Reset your Caderly password" |
| Leave requested | Approver (manager + Admin fallback) | "{Employee} requested {Leave Type} — {duration}" |
| Leave approved | Employee | "Your {Leave Type} was approved" |
| Leave rejected | Employee | "Your {Leave Type} request was declined" |
| Leave cancelled by employee | Approver | "{Employee} cancelled their leave" |
| Document expiring | Employee + Admin | "{Document} expires in {N} days" |
| Public holiday tomorrow | All active users | "Reminder: {Holiday} tomorrow" |
| Birthday | Team members of celebrant | "🎂 It's {Employee}'s birthday today" (opt-in per tenant) |
| Work anniversary | Team | "🎉 {Employee} joined {tenant} {N} years ago today" (opt-in) |

### 17.3 Delivery
- Durable outbox, never `@Async`: the triggering action writes an `email_outbox` row **in its own transaction**, and a `@Scheduled` dispatcher polls the table and performs the SMTP send (CLAUDE.md §6a). An in-memory async queue would silently drop pending mail on restart.
- Scheduled retry: 3 attempts with exponential backoff (30s → 2m → 10m), then mark FAILED and log.
- Test SMTP via MailHog in dev.

### 17.4 Preferences (Phase 2)
- Per-user opt-in/out per category.
- Digest mode (daily summary).

---

## 18. Audit Logs — Detailed

### 18.1 What's captured

**Write audit** (`audit_entry` table):
- `id, tenant_id, actor_user_id, actor_role, occurred_at, entity_type, entity_id, action (CREATE|UPDATE|DELETE), before_json, after_json, ip, user_agent, request_id`.

**Login audit** (`login_audit` table):
- `id, tenant_id, user_id (nullable if unknown), occurred_at, email_attempted, success (bool), failure_reason (nullable), ip, user_agent, request_id`.

### 18.2 Implementation
- Hibernate `@EntityListeners` on all auditable entities → captures before/after in a `@PostPersist` / `@PreUpdate` / `@PreRemove`.
- Alternatively, Envers — but Envers is heavier and less flexible; roll our own for tighter control.
- MDC (Mapped Diagnostic Context) propagates request_id, actor, tenant across threads for correlated logs.
- Login events emitted from Spring Security `AuthenticationEventPublisher`.

### 18.3 UI (Admin only)
- Table: date, actor, entity, action, "view diff" (opens modal with before/after JSON).
- Filters: date range, actor, entity type, action.
- CSV export (Phase 2).

### 18.4 Retention
- Default 7 years (configurable per tenant).
- Nightly job archives entries > retention to cold storage (S3 Glacier) and deletes from Postgres. Phase 2.

---

## 19. Security Requirements

### 19.1 Authentication
- Passwords: BCrypt cost 12, min length 10, must have upper+lower+digit, blocked-common-passwords list. *(Composition rules shipped in 1.2. The blocklist is **not implemented** — a hand-written list is theatre at any size a human maintains, and the composition rules already exclude nearly everything such a list would contain. Needs either the HaveIBeenPwned range API or a bundled breach corpus; deferred as a decision in its own right, see ADR 0006 decision F.)*
- Reset tokens: 32-byte cryptographic random, single-use, 24h TTL, stored hashed.
- Session: **server-side HTTP session cookie** (`JSESSIONID`), `HttpOnly` + `Secure` + `SameSite=Lax`. Spring Security default. Idle timeout 8 h (configurable per tenant); absolute timeout 24 h. Session state stored in-JVM for the single-instance first-customer deployment; when we go multi-instance, swap in `spring-session-jdbc` and store sessions in Postgres — no application-code change. **Revocation** is immediate: password change, role change, or termination invalidates all sessions for that user by deleting them from the session store.
- Lockout: 5 failures / 15 min per (email + IP).
- MFA (TOTP): optional per user, enforceable per tenant for Admin role.

### 19.2 Authorization
- Every controller method annotated with `@PreAuthorize("hasRole('...')")`.
- Row-level: enforced in ORM via tenant filter + additional per-entity ownership checks (e.g., leave request owner must be current user or their manager or Admin).
- Cross-tenant references impossible: any FK must resolve within `TenantContext`.

### 19.3 Transport
- HTTPS only. HSTS max-age 1 year with preload.
- HTTP → HTTPS 301.
- TLS 1.2+, modern ciphers only.

### 19.4 Data at rest
- Postgres data volume: LUKS-encrypted disk on VPS, or provider-managed encryption on cloud.
- Column-level encryption (AES-256-GCM) for `bank_details.*`, `government_id.number`, `compensation` (if tenant flag set). Key managed via env var / KMS.
- File storage: server-side encryption for S3; encrypted filesystem for local.

### 19.5 Input & output
- All input validated with Bean Validation (`@Valid`).
- All output HTML-escaped by Thymeleaf default (`th:text`).
- CSRF token on all state-changing forms (Spring Security default).
- SQL: only parameterized queries (JPA + JPQL + native prepared statements). No string concatenation.
- File upload: MIME + magic-byte check + extension whitelist + size limit + AV scan (ClamAV, optional per deployment).

### 19.6 Headers
- Content-Security-Policy: `default-src 'self'; script-src 'self' 'unsafe-inline' cdn.jsdelivr.net unpkg.com; style-src 'self' 'unsafe-inline' cdn.jsdelivr.net; img-src 'self' data:; frame-ancestors 'none';` — refined per deployment.
- X-Frame-Options: DENY.
- X-Content-Type-Options: nosniff.
- Referrer-Policy: strict-origin-when-cross-origin.
- Permissions-Policy: minimal.

### 19.7 Rate limiting
- Login endpoint: 10 req/min per IP.
- Password reset request: 3 req/hour per email.
- General API: 300 req/min per session.
- Implemented via Bucket4j (in-JVM) or Redis if multi-instance.

### 19.8 Backup
- Postgres: nightly `pg_dump` compressed → local + off-site (S3). Retained 30 days.
- Files: S3 versioning, or nightly rsync for local backend.
- Restore drill: quarterly.

### 19.9 Vulnerability management
- Dependabot on GitHub for library updates.
- Snyk or Trivy scanning Docker image in CI.

### 19.10 Secrets
- Never in code. Loaded from env vars (12-factor). `.env.example` shipped, real `.env` in ops repo or Vault.

---

## 20. Multi-Tenancy Design

### 20.1 Chosen model
**Shared database, shared schema, `tenant_id` column on every row.**

### 20.2 Rationale
- Baseline scale (50 tenants × 50 employees = 2,500 employees total) fits comfortably in one Postgres instance.
- Single migration path (one Flyway history).
- Cheapest to operate, easiest to back up and monitor.
- Postgres RLS (Row-Level Security) available as second line of defense.

### 20.3 Tenant resolution
1. Request arrives at `acme.caderly.app`.
2. `TenantResolutionFilter` (Spring Filter, first in chain) extracts subdomain, looks up tenant row by slug, and populates `TenantContext` (ThreadLocal → InheritableThreadLocal, plus contextual copy for reactive/async).
3. `TenantContext` cleared in `finally` block to prevent leak between requests.
4. If tenant not found → 404 with generic "Unknown tenant" page.
5. If tenant suspended → 503 page.

### 20.4 Enforcement (defense in depth)
1. **ORM restriction:** every tenant-aware entity's `tenant_id` field is annotated `@TenantId` (Hibernate discriminator multi-tenancy, Hibernate 7). Hibernate arms this restriction itself — from a `CurrentTenantIdentifierResolver` bean (`TenantIdentifierResolver`) — on every query it generates for that entity, including `find(id)`; there is no hand-written `@Filter` and no AOP interceptor to opt it in per call (see ADR 0004).
2. **Auto-set on write:** Hibernate's own `@TenantId` column generation sets `tenant_id` from the same resolver on insert; no `@PrePersist` listener is involved.
3. **Postgres RLS:** per-table policy `USING (tenant_id = current_setting('app.tenant_id')::uuid)`. Set via `SET LOCAL app.tenant_id = ...` at the start of every transaction by `TenantSessionVariableListener` (a Spring `TransactionExecutionListener`, not an AOP aspect). This catches bugs independently of the ORM layer — including the (rare, deliberately audited) case where the ORM restriction is pointed at a placeholder tenant under `TenantContext.runAsSystem`.
4. **ArchUnit tests:** require every tenant-scoped entity to extend `common.TenantAwareEntity`; forbidding direct JDBC/native-query usage outside repositories is planned but not yet enforced (CLAUDE.md §8).
5. **Integration tests:** for every write path, assert audit log has correct `tenant_id`; for every read, seed 2 tenants and assert results are scoped.

### 20.5 Cross-tenant operations
Only Super Admin console (behind IP allowlist + separate authentication realm) may bypass filters, using explicit `TenantContext.runAsSystem(reason, action)`. Every call requires a reason string and is logged; all such bypasses audit-log with `SYSTEM` actor.

### 20.6 Backup & restore per tenant
- Full DB backup covers all tenants (single restore = all).
- Per-tenant restore requires custom script: `pg_dump --data-only --where "tenant_id='...'" per table`. Documented but not automated in MVP.

### 20.7 Suspension & deletion
- Suspend: set `tenant.suspended = true`. Filter in login flow rejects.
- Delete: 30-day soft-delete window (`tenant.deleted_at`), then cascade hard-delete via scheduled job that deletes rows per table where `tenant_id = ?`.

---

## 21. Database Schema (core tables)

Naming: snake_case, plural (Hibernate default). PKs are `uuid` v7 (time-ordered) for tenant-scoped tables — pluggable to `bigserial` if desired. Timestamps stored as `timestamptz` in UTC.

```sql
-- ============ Core cross-tenant ============
CREATE TABLE tenant (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug varchar(50) NOT NULL UNIQUE,
  name varchar(200) NOT NULL,
  logo_url varchar(500),
  -- No primary_color: one Caderly brand color for every tenant, in the app UI and in
  -- transactional email alike (ADR 0016, sub-phase 1.10). A tenant's only visual
  -- customization is its logo, shown as a guest beside the wordmark, not in place of it.
  timezone varchar(50) NOT NULL DEFAULT 'UTC',
  locale varchar(10) NOT NULL DEFAULT 'en',
  weekend_days int NOT NULL DEFAULT 96, -- bitmask: Sat=64, Sun=32 -> 96
  suspended boolean NOT NULL DEFAULT false,
  -- FR-9.3 per-tenant notification categories (sub-phase 1.10, ADR 0016). Birthday and
  -- work anniversary default false ("opt-in per tenant" per §17.2); the leave lifecycle,
  -- invite, and password reset have no switch — they are the product working, not a category.
  notify_holiday_reminder boolean NOT NULL DEFAULT true,
  notify_document_expiry boolean NOT NULL DEFAULT true,
  notify_birthday boolean NOT NULL DEFAULT false,
  notify_work_anniversary boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz
);

CREATE TABLE super_admin (
  id uuid PRIMARY KEY,
  email varchar(255) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  mfa_secret varchar(100),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ============ Identity ============
CREATE TABLE app_user (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  email varchar(255) NOT NULL,
  password_hash varchar(100),
  mfa_secret varchar(100),
  mfa_enabled boolean NOT NULL DEFAULT false,
  status varchar(20) NOT NULL, -- INVITED, ACTIVE, LOCKED, DISABLED
  last_login_at timestamptz,
  failed_login_count int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  invite_token_hash varchar(100),
  invite_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);

CREATE TABLE user_role (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role varchar(20) NOT NULL, -- EMPLOYEE, MANAGER, ADMIN
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, role)
);
-- Synthetic UUID PK (not the natural composite (user_id, role)) so this
-- table extends TenantAwareEntity like every other tenant-scoped table
-- and gets Hibernate 7 @TenantId + RLS for free per CLAUDE.md §5.
-- The UNIQUE constraint preserves the composite-key semantic. See ADR-0005.
CREATE INDEX idx_user_role_user ON user_role(tenant_id, user_id);
CREATE INDEX idx_user_role_role ON user_role(tenant_id, role);

CREATE TABLE password_reset_token (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  token_hash varchar(100) NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);

-- ============ Organization ============
CREATE TABLE division (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  name varchar(150) NOT NULL,
  description text,
  archived boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

CREATE TABLE department (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  division_id uuid NOT NULL REFERENCES division(id),
  name varchar(150) NOT NULL,
  description text,
  head_employee_id uuid, -- FK to employee, nullable
  archived boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);

-- ============ Employee ============
CREATE TABLE employee (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  user_id uuid REFERENCES app_user(id), -- nullable during INVITED before user exists
  employee_code varchar(50),
  first_name varchar(100) NOT NULL,
  last_name varchar(100) NOT NULL,
  email varchar(255) NOT NULL,
  phone varchar(30),
  photo_url varchar(500),
  birth_date date,
  gender varchar(20),
  marital_status varchar(20),
  nationality varchar(100),
  citizenship varchar(100),
  address_line1 varchar(200),
  address_line2 varchar(200),
  city varchar(100),
  country varchar(100),
  postal_code varchar(20),
  hire_date date,
  termination_date date,
  employment_type varchar(20), -- FULL_TIME, PART_TIME, CONTRACT, INTERN
  status varchar(20) NOT NULL, -- INVITED, ACTIVE, TERMINATED
  department_id uuid REFERENCES department(id),
  manager_id uuid REFERENCES employee(id),
  job_title varchar(150),
  work_location varchar(150),
  working_hours_per_day numeric(4,2) DEFAULT 8.0,
  currency varchar(3),
  base_compensation_encrypted bytea,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);

CREATE INDEX idx_employee_tenant ON employee(tenant_id);
CREATE INDEX idx_employee_manager ON employee(tenant_id, manager_id);
CREATE INDEX idx_employee_dept ON employee(tenant_id, department_id);

CREATE TABLE employee_manager_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  manager_id uuid REFERENCES employee(id),
  effective_from date NOT NULL,
  effective_to date
);

CREATE TABLE employee_status_history (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  status varchar(20) NOT NULL,
  employment_type varchar(20),
  effective_from date NOT NULL,
  effective_to date
);

CREATE TABLE education (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  institution varchar(200),
  degree varchar(150),
  field varchar(150),
  start_year int,
  end_year int
);

CREATE TABLE emergency_contact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  name varchar(150) NOT NULL,
  relationship varchar(50),
  phone varchar(30),
  email varchar(255)
);

CREATE TABLE government_id (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  id_type varchar(50) NOT NULL, -- PASSPORT, EMIRATES_ID, VISA, NATIONAL_ID, OTHER
  id_number_encrypted bytea NOT NULL,
  country varchar(100),
  issue_date date,
  expiry_date date
);

CREATE TABLE bank_detail (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id) UNIQUE,
  bank_name varchar(200),
  account_name_encrypted bytea,
  account_number_encrypted bytea,
  iban_encrypted bytea,
  swift_bic varchar(20)
);

CREATE TABLE benefit (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  name varchar(150),
  category varchar(100),
  start_date date,
  end_date date
);

-- ============ Files ============
CREATE TABLE company_file (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  name varchar(300) NOT NULL,
  mime varchar(100),
  size_bytes bigint,
  storage_key varchar(500) NOT NULL,
  uploaded_by uuid NOT NULL REFERENCES app_user(id),
  uploaded_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE employee_document (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  name varchar(300) NOT NULL,
  mime varchar(100),
  size_bytes bigint,
  storage_key varchar(500) NOT NULL,
  visibility varchar(20) NOT NULL, -- EMPLOYEE_PRIVATE, ADMIN_ONLY
  uploaded_by uuid NOT NULL REFERENCES app_user(id),
  uploaded_at timestamptz NOT NULL DEFAULT now()
);

-- ============ Leave ============
CREATE TABLE leave_type (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  name varchar(100) NOT NULL,
  icon varchar(50),
  color varchar(7),
  paid boolean NOT NULL DEFAULT true,
  allows_half_day boolean NOT NULL DEFAULT true,
  allows_backdated boolean NOT NULL DEFAULT false,
  requires_approval boolean NOT NULL DEFAULT true,
  default_annual_allowance numeric(5,2) NOT NULL DEFAULT 0,
  description text,
  active boolean NOT NULL DEFAULT true,
  UNIQUE (tenant_id, name)
);

CREATE TABLE public_holiday (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  date date NOT NULL,
  name varchar(200) NOT NULL,
  UNIQUE (tenant_id, date, name)
);

CREATE TABLE leave_balance (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  leave_type_id uuid NOT NULL REFERENCES leave_type(id),
  year int NOT NULL,
  granted numeric(5,2) NOT NULL,
  used numeric(5,2) NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, employee_id, leave_type_id, year)
);

CREATE TABLE leave_request (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  leave_type_id uuid NOT NULL REFERENCES leave_type(id),
  start_date date NOT NULL,
  end_date date NOT NULL,
  start_half_day_pm boolean NOT NULL DEFAULT false,
  end_half_day_am boolean NOT NULL DEFAULT false,
  duration_days numeric(5,2) NOT NULL,
  status varchar(20) NOT NULL, -- PENDING, APPROVED, REJECTED, CANCELLED
  note text,
  submitted_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  decider_id uuid REFERENCES app_user(id),
  decision_note text,
  cancelled_at timestamptz,
  cancelled_by uuid REFERENCES app_user(id)
);

CREATE INDEX idx_leave_req_emp_period ON leave_request(tenant_id, employee_id, start_date, end_date);
CREATE INDEX idx_leave_req_status ON leave_request(tenant_id, status);

-- ============ System-scoped infrastructure tables ============
-- Notifications, audit, and login-audit tables are NOT tenant-scoped in the
-- ORM sense: their Java entities do NOT extend TenantAwareEntity, do NOT have
-- @TenantId, and do NOT have RLS. The tenant_id column is a nullable
-- *reference* used for admin-viewer filtering and for the dispatcher to look
-- up per-tenant branding. Background jobs (EmailDispatcher, audit archival)
-- query these tables system-scoped without a TenantContext. Any admin viewer
-- of these tables filters WHERE tenant_id = :currentTenant explicitly in the
-- controller/service — defense in depth for system tables. See ADR 0005 and
-- CLAUDE.md §5.1 exemption + Implementation Plan §1.2.

-- ============ Notifications ============
CREATE TABLE email_outbox (
  id uuid PRIMARY KEY,
  tenant_id uuid REFERENCES tenant(id), -- nullable reference, not RLS discriminator
  to_email varchar(255) NOT NULL,
  subject varchar(500) NOT NULL,
  body_html text NOT NULL,
  status varchar(20) NOT NULL, -- PENDING, SENT, FAILED
  attempts int NOT NULL DEFAULT 0,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  next_attempt_at timestamptz
);

CREATE INDEX idx_email_outbox_pending ON email_outbox(status, next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_email_outbox_tenant ON email_outbox(tenant_id, created_at DESC);

-- ============ Audit ============
CREATE TABLE audit_entry (
  id bigserial PRIMARY KEY,
  tenant_id uuid, -- nullable reference, not RLS discriminator (system actions have no tenant)
  actor_user_id uuid,
  actor_role varchar(20),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  entity_type varchar(100) NOT NULL,
  entity_id varchar(50),
  action varchar(20) NOT NULL, -- CREATE, UPDATE, DELETE, SYSTEM
  before_json jsonb,
  after_json jsonb,
  ip varchar(45),
  user_agent varchar(500),
  request_id varchar(50)
);

CREATE INDEX idx_audit_tenant_time ON audit_entry(tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_entry(tenant_id, entity_type, entity_id);

CREATE TABLE login_audit (
  id bigserial PRIMARY KEY,
  tenant_id uuid, -- nullable reference, not RLS discriminator (unknown-tenant attempts land here too)
  user_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  email_attempted varchar(255),
  success boolean NOT NULL,
  failure_reason varchar(100),
  ip varchar(45),
  user_agent varchar(500),
  request_id varchar(50)
);

-- ============ RLS example (applied to every tenant-scoped table) ============
ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee
  USING (tenant_id::text = current_setting('app.tenant_id', true));
```

### 21.1 Migrations
- Flyway, one file per change, `V<yyyymmddhhmm>__<description>.sql`.
- Repeatable views/functions in `R__*.sql`.
- Never edit an applied migration; always add a new one.

---

## 22. Domain Model (Java packages)

```
com.caderly.caderlyhr
├── identity                # AppUser, UserRole, PasswordResetToken, TotpSecret
├── tenant                  # Tenant, TenantContext, TenantResolutionFilter
├── org                     # Division, Department
├── people                  # Employee, Education, EmergencyContact, GovernmentId, BankDetail, Benefit
│                           # EmployeeStatusHistory, EmployeeManagerHistory
├── timeoff                 # LeaveType, LeaveBalance, LeaveRequest, PublicHoliday
├── documents               # CompanyFile, EmployeeDocument
├── notifications           # EmailOutbox, EmailTemplate, TransactionalMailer
├── audit                   # AuditEntry, LoginAudit, AuditListener
├── reports                 # ReportService, LeaveBalanceReport, UtilizationReport, HeadcountReport
├── storage                 # FileStorage interface, LocalFileStorage, S3FileStorage
├── security                # SecurityConfig, TotpService, PasswordEncoder, RateLimiter
├── web                     # Controllers (Thymeleaf), FragmentAdvice, GlobalExceptionHandler
├── api                     # REST controllers (v1)
├── superadmin              # Super-admin console (separate security realm)
└── common                  # BaseEntity, TenantAwareEntity, exceptions, utils
```

Package cycle prevention: **ArchUnit** test forbids `people` from depending on `timeoff` (leaks) but allows `timeoff` to reference `people.Employee` by ID via a `PeopleFacade`.

---

## 23. API Design

REST + JSON, versioned under `/api/v1`. All endpoints tenant-scoped by subdomain. OpenAPI 3 auto-generated via springdoc, browsable at `/api/docs`.

### 23.1 Conventions
- Nouns, plural: `/employees`, `/leave-requests`.
- HTTP methods: GET (read), POST (create/action), PATCH (partial update), PUT (rare), DELETE (soft).
- Filtering: query params (`?department=<id>&status=ACTIVE`).
- Pagination: `?page=0&size=20`; response wraps in `{content, page, size, totalElements, totalPages}`.
- Sorting: `?sort=lastName,asc`.
- Errors: RFC 7807 Problem Details.
- Auth: session cookie for web; Bearer token for API (Phase 2).

### 23.2 Key endpoints (MVP)

**Auth:**
- `POST /api/v1/auth/login` → `{email, password}` → sets cookies
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/forgot-password` → `{email}`
- `POST /api/v1/auth/reset-password` → `{token, newPassword}`
- `POST /api/v1/auth/mfa/enable` (returns provisioning URI)
- `POST /api/v1/auth/mfa/verify`

**Employees:**
- `GET /api/v1/employees` (list, filters)
- `GET /api/v1/employees/{id}`
- `POST /api/v1/employees` (Admin)
- `PATCH /api/v1/employees/{id}` (self limited; Admin full)
- `POST /api/v1/employees/{id}/terminate`
- `POST /api/v1/employees/{id}/reinvite`
- Sub-resources: `/education`, `/emergency-contacts`, `/government-ids`, `/bank-detail`, `/benefits`, `/documents`

**Organization:**
- `GET/POST/PATCH/DELETE /api/v1/divisions`
- `GET/POST/PATCH/DELETE /api/v1/departments`

**Leave:**
- `GET/POST/PATCH/DELETE /api/v1/leave-types` (Admin)
- `GET /api/v1/leave-balances?employeeId=&year=` (self or Admin)
- `POST /api/v1/leave-requests` (self)
- `GET /api/v1/leave-requests?scope=me|team|all&status=`
- `POST /api/v1/leave-requests/{id}/approve`
- `POST /api/v1/leave-requests/{id}/reject`
- `POST /api/v1/leave-requests/{id}/cancel`
- `POST /api/v1/public-holidays/bulk-upload` (Admin, CSV)

**Files:**
- `GET /api/v1/company-files`, `POST /api/v1/company-files` (Admin), `DELETE /api/v1/company-files/{id}` (Admin)
- `GET/POST/DELETE /api/v1/employees/{id}/documents`

**Calendar:**
- `GET /api/v1/calendar/team?from=&to=&departmentId=`
- `GET /api/v1/calendar/ical.ics?token=<user_token>` (public, no auth, per-user token)

**Reports:**
- `GET /api/v1/reports/leave-balance.csv?departmentId=`
- `GET /api/v1/reports/leave-utilization.csv?from=&to=&departmentId=`
- `GET /api/v1/reports/headcount.csv?from=&to=`

**Audit (Admin):**
- `GET /api/v1/audit?entityType=&actorId=&from=&to=&page=`
- `GET /api/v1/audit/logins?success=&from=&to=`

**Super Admin (separate host):**
- `POST /superadmin/tenants`
- `PATCH /superadmin/tenants/{id}/suspend`
- `DELETE /superadmin/tenants/{id}`

### 23.3 Error format (RFC 7807)
```json
{
  "type": "https://caderly.app/errors/insufficient-balance",
  "title": "Insufficient leave balance",
  "status": 422,
  "detail": "Requested 3 days but only 2 available for Vacation.",
  "instance": "/api/v1/leave-requests",
  "errorCode": "LEAVE_INSUFFICIENT_BALANCE"
}
```

---

## 24. UI Wireframes (textual)

### 24.1 Layout shell
- Top bar (fixed): tenant logo (left) · search (center) · "Book time off" CTA · user avatar + menu (right)
- Left sidebar (collapsible): Home · People · My Profile · Files · Calendar · For Action · (Admin only) Admin · (Admin only) Reports
- Content area: page content, responsive down to 375 px mobile.

### 24.2 Home Dashboard
Widget grid (customizable, MVP presets):
- **Welcome** — "Welcome, {FirstName}!"
- **Book Time Off** — 3 cards showing remaining balance per leave type; click a card → pre-selects that type.
- **My Peers** — avatars + names of same-department + same-manager peers; tabs: All / Out today.
- **Time Off Today** — circular chart of who's out today across tenant.
- **My Days Off** — upcoming approved leave list.
- **Company News** — Admin posts (Phase 2). MVP: static "Welcome to Caderly" tile.
- **Resources** — links to top 3 company files.

> **Sub-phase 1.9 / ADR 0015:** the shipped MVP grid swaps **Company News** for **Upcoming
> Holidays** (§8 feature #13) — Company News's MVP form has no per-tenant data behind it, and
> §24.2 vs. §8 disagreed on the sixth slot. "Welcome" ships as the page's `<h1>` heading, not a
> grid card, so the six cards above (with Upcoming Holidays in place of Company News) fill UI
> Guidelines §8.2's 6-widget cap exactly.

### 24.3 People
- Header: "People (8)" · Search filter · View toggles: List / Grid / Org Tree.
- List: Name, Department, Division, Type, Hire Date, Contact Details, actions (view profile).
- Sortable columns. Filter panel: department, division, employment type, status.

### 24.4 Profile (own or peer)
- Left column: avatar (edit for self), name, role badge, hire date + tenure ("7Y 5M 20D"), department icon, job title icon, location icon, Manager section, "My peers" avatars, Contact details.
- Right column: tabs — Personal | Education | Job | Documents | Tasks | Time Off.
- Personal tab: Basic info (First name, Last name, Email, Phone, Employee code, Birth date, Gender, Marital status, Nationality, Citizenship, Address, City, Country, Postal code) + Emergency contacts + Government IDs.
- Job tab: Employment status history table, Manager history table, Job info card, Compensation (Admin only), Benefits table.
- Documents tab: employee docs list (upload button).
- Tasks tab: pending/completed tabs, task list.
- Time Off tab: Budget cards per leave type with "Budget for period" year selector, History table.

### 24.5 Calendar
- Header: month/week selector · Today button · Filter panel button · View toggle (Grid / List).
- Grid: rows = employees (avatar + name), columns = days of period. Cells with colored bars for leave; icon = leave type. Hover shows tooltip with dates + duration.
- Filter panel: department, division, leave type.

### 24.6 For Action
- Two panels: "Tasks" tab · "Time off requests" tab.
- Time off requests: sub-tabs Pending (n) / Completed (n).
- Each row: leave type icon, employee name, dates, duration, request date, note, Approve / Reject buttons.
- Approve/Reject opens modal for optional note.

### 24.7 Files
- Table: Name, Size, Date added, Download / Delete (Admin).
- Upload button (Admin).

### 24.8 Book Time Off Modal
- Type selector (chip row per leave type with balance badge).
- Date range picker (start, end); half-day toggles for start (PM) and end (AM).
- Live "This will use N working days" indicator.
- Note textarea.
- Submit button (disabled if insufficient balance, with reason).

### 24.9 Admin console (`/admin`)
- Sub-nav: Employees · Divisions · Departments · Leave Types · Holidays · Notifications · Audit Log · Settings.
- Each is a CRUD grid with slide-over form.

### 24.10 Settings (per-user)
- Tabs: General (timezone, language) · Integrations (calendar iCal URL) · Change password · MFA.

### 24.11 Super Admin console (subdomain or path-guarded)
- Tenants table: slug, name, employees count, status, actions (Suspend, Impersonate, Delete).
- Create tenant form.

---

## 25. Navigation Flow

```
                       ┌───────────────┐
                       │   Login page  │
                       └───────┬───────┘
                               │ success
                    ┌──────────▼─────────────┐
                    │       Home Dashboard   │
                    └──┬──────────┬──────────┘
       ┌───────────────┼──────────┼───────────────────┐
       ▼               ▼          ▼                   ▼
 ┌─────────┐   ┌──────────┐  ┌─────────┐        ┌─────────┐
 │  People │   │  My      │  │ Calendar│        │  For    │
 │         │   │ Profile  │  │         │        │ Action  │
 └────┬────┘   └────┬─────┘  └────┬────┘        └────┬────┘
      │             │             │                  │
      ▼             ▼             ▼                  ▼
   Person       Profile        Book Time         Approve/
   Profile      Tabs           Off Modal         Reject
                                                 Modal
                    ┌──────────────────┐
                    │  Admin Console   │  (Admin role only)
                    │  ─────────────── │
                    │  • Employees     │
                    │  • Org Units     │
                    │  • Leave Types   │
                    │  • Holidays      │
                    │  • Audit Log     │
                    │  • Reports       │
                    │  • Settings      │
                    └──────────────────┘

Top-bar "Book time off" opens the modal from any page.
User-avatar menu → My Profile, My Settings, Calendar Integration, Change Password, Logout.
```

---

## 26. Permissions Matrix

Legend: ✅ full · 👤 self only · 👥 direct + indirect reports · 🔒 read-only · ❌ none.

| Resource / Action | Employee | Manager | Admin | Super Admin |
|---|---|---|---|---|
| View own profile | ✅ | ✅ | ✅ | ❌ (not in tenant) |
| Edit own personal info | ✅ | ✅ | ✅ | ❌ |
| Edit own job/compensation | ❌ | ❌ | ✅ | ❌ |
| View peer profile | 🔒 basic | 🔒 basic | 🔒 full | ❌ |
| View report's profile | ❌ | 🔒 full | 🔒 full | ❌ |
| Create/edit/terminate employee | ❌ | ❌ | ✅ | ❌ |
| CRUD divisions/departments | ❌ | ❌ | ✅ | ❌ |
| CRUD leave types | ❌ | ❌ | ✅ | ❌ |
| Upload public holidays | ❌ | ❌ | ✅ | ❌ |
| Book own leave | ✅ | ✅ | ✅ | ❌ |
| Cancel own leave | ✅ (rules apply) | ✅ | ✅ | ❌ |
| Approve/reject leave | ❌ | 👥 reports | ✅ any | ❌ |
| Override any leave decision | ❌ | ❌ | ✅ | ❌ |
| View team calendar | ✅ all | ✅ all | ✅ all | ❌ |
| Upload company file | ❌ | ❌ | ✅ | ❌ |
| Upload own document | ✅ | ✅ | ✅ | ❌ |
| Upload document on behalf | ❌ | ❌ | ✅ | ❌ |
| View audit log | ❌ | ❌ | ✅ | ✅ cross-tenant |
| Generate reports | ❌ | ❌ | ✅ | ❌ |
| Configure tenant settings | ❌ | ❌ | ✅ | ✅ cross-tenant |
| Create/suspend/delete tenant | ❌ | ❌ | ❌ | ✅ |
| Impersonate Admin (audit-logged) | ❌ | ❌ | ❌ | ✅ |

---

## 27. Architecture Diagram (textual)

```
                     ┌─────────────────────────────────────┐
                     │            End User (browser)       │
                     │  {tenant}.caderly.app                 │
                     └───────────────────┬─────────────────┘
                                         │ HTTPS (TLS 1.3)
                                ┌────────▼────────┐
                                │  Caddy reverse  │  Auto Let's Encrypt cert
                                │  proxy (or Nginx│  Wildcard *.caderly.app
                                └────────┬────────┘
                                         │
                       ┌─────────────────▼─────────────────┐
                       │      Spring Boot 4.1 (Java 25)    │
                       │  ┌───────────────────────────────┐ │
                       │  │ TenantResolutionFilter        │ │
                       │  │ SecurityFilterChain           │ │
                       │  │ AuditListener (@EntityListen) │ │
                       │  ├───────────────────────────────┤ │
                       │  │ Web (Thymeleaf) │  REST /api  │ │
                       │  ├─────────────────┴─────────────┤ │
                       │  │ Application services          │ │
                       │  │ Domain modules (people, org,  │ │
                       │  │ timeoff, docs, notif, audit)  │ │
                       │  ├───────────────────────────────┤ │
                       │  │ Spring Data JPA + Hibernate   │ │
                       │  │ Hibernate @TenantId (7.x)     │ │
                       │  └────────┬───────────────┬──────┘ │
                       │           │               │        │
                       │  ┌────────▼───┐   ┌───────▼─────┐  │
                       │  │Caffeine    │   │@Scheduled + │  │
                       │  │cache (JVM) │   │ ShedLock    │  │
                       │  └────────────┘   └─────────────┘  │
                       └────────┬────────────────┬──────────┘
                                │                │
                     ┌──────────▼─────────┐  ┌──────▼──────────┐
                     │ PostgreSQL 17      │  │ FileStorage impl│
                     │ (host-native, NOT  │  │  Local FS  │ S3 │
                     │  in Docker Compose)│  └─────────────────┘
                     │ Row-Level Security │
                     │ Encrypted disk     │
                     └────────────────────┘
                                │
                     ┌──────────▼─────────────┐
                     │ Nightly pg_dump backup │
                     │  → local + S3 off-site │
                     └────────────────────────┘

                     ┌──────────────────────────┐
                     │ SMTP provider (any)      │  Async via email_outbox
                     │ Postfix / SES / Postmark │
                     └──────────────────────────┘

Observability: Actuator → Prometheus → Grafana (optional per deployment)
Logs: JSON to stdout → captured by Docker or systemd journal
```

**Deployment topology (first customer, on-prem Debian):**
- Single VPS. **PostgreSQL 17 installed natively** via `apt` on the host (not in a container — simpler backup, better perf, direct pg_dump). Docker Compose runs only 2 services: `caderly-app` (Spring Boot) and `caddy` (reverse proxy). The containers use host networking: the app connects to the host Postgres on `localhost` and listens on `127.0.0.1` only, so Caddy is the sole externally reachable listener (ADR 0023). LUKS-encrypted disk. Daily cron for `pg_dump`. `.env` for secrets (DB URL, credentials, encryption key, SMTP).

**Deployment topology (future cloud tenant):**
- Same Docker image. RDS/Cloud SQL for Postgres. S3 for files. Application Load Balancer with wildcard cert. Optional: run 2 app replicas + ShedLock for scheduler safety.

---

## 28. Future Enhancements (roadmap tail)

- SSO: Google, Microsoft, Apple, SAML.
- Custom domain per tenant (`hr.example.com`).
- Attendance / clock-in-out with mobile app.
- Performance reviews module (OKRs, 1:1 notes, 360 reviews).
- Onboarding/offboarding checklist templates & workflow engine.
- Recruitment / ATS.
- Learning Management System.
- Payroll integration presets per country.
- Public REST API + tenant API keys.
- Webhooks (per-tenant configurable, e.g. leave.approved).
- Slack + MS Teams native apps.
- Multi-currency compensation with FX rates.
- Delegation ("I'm out — approve to X").
- Approval policies per leave type (single, dual, HR).
- Advanced org: N-level hierarchy, matrix orgs.
- AI-assisted policy Q&A (tenant handbook indexed).
- Skills & competencies catalog.
- Compensation planning module.
- Diversity & inclusion analytics.

---

## 29. Risks and Assumptions

### 29.1 Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Solo developer bus factor | High | High | Externalize documentation, keep architecture simple, invest in tests, publish repo internally. |
| Tenant isolation bug leaks data | Low | Catastrophic | Defense in depth (filter + RLS + tests + code review). CI test suite fails on any cross-tenant read. |
| Scope creep from first-customer users after MVP | Medium | Medium | Enforce backlog + phases. Add "phase 3+" queue in issue tracker; require ROI note on every new request. |
| Grails-to-Spring learning curve | Medium | Low-Medium | Concepts map cleanly; budget 2 weeks of ramp before starting real features. Book: "Spring Boot in Action". |
| Free plan feature-parity moving target | Medium | Low | TalentHR could change features. Snapshot their free-plan spec now (screenshots + this PRD) and freeze scope. |
| Email deliverability | Medium | Medium | Start with provider (Postmark/SES) with SPF/DKIM/DMARC properly configured. Never send from generic Gmail. |
| Migration from TalentHR (data import) | Medium | Medium | Build CSV import for employees + leave balances first thing after core CRUD. |
| Losing data before backups exist | Medium | High | Set up nightly pg_dump before ANY real customer data enters system. Restore drill within first month. |
| Weak passwords on Admin accounts | Medium | High | Enforce MFA for Admins from Phase 1.5. |
| Time zone bugs in leave duration | High (bug type) | Medium | Store all timestamps UTC; do all date math in tenant timezone; unit-test around DST, year boundary, week boundary. |
| PDF/CSV report scale | Low | Low | For MVP volumes, materialized-view + Postgres is fine. Revisit if a tenant grows past 1000 employees. |
| Legal/labor-law compliance per region | Medium | Medium | We're region-agnostic by design. Explicit "you are responsible for policy compliance" clause in ToS. |
| GDPR data-subject requests | Low (no EU tenants yet) | Medium | Design "export my data" endpoint from day 1 even if UI is Phase 2. |

### 29.2 Assumptions

- The first customer has fewer than 20 employees for at least 12 months → single Spring Boot instance handles it trivially.
- No hard SLA to external customers in year 1 (first customer only + friendly pilots).
- English UI is acceptable for launch even for UAE (Arabic in Phase 2).
- Weekend definition is uniform across the whole tenant (no per-employee weekends).
- Manager is a single person (no dotted-line matrix in MVP).
- One year = Jan 1–Dec 31 for balance grants (no fiscal-year offset in MVP).
- Employees have a valid email address (required, unique per tenant).
- No physical time-clock integration in MVP.
- Tenant sub-domain DNS is manageable by us (wildcard *.caderly.app).
- All tenants trust Caderly-provided storage; nobody insists on their own S3 bucket in MVP.
- The first customer can provide test data and act as design partner.
- Spring Boot 4.1.0 remains stable through the MVP build cycle (released June 2026, LTS-track).

---

*End of PRD — v1.0*
