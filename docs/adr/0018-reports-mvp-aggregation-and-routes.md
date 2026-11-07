# ADR 0018 — Reports MVP: CSV route shape, aggregation ownership, and headcount department scope

**Status:** Accepted
**Date:** 2026-09-19
**Deciders:** Hasan (solo dev)
**Relates to:** CLAUDE.md §4 (facade boundary), PRD §16 and §23.2

---

## Context

Phase 1.12 (Reports MVP) needed three decisions the PRD and Implementation Plan named but left
open: where the CSV download endpoints should live, how SUM/COUNT/GROUP BY aggregation should be
structured given the codebase had zero existing GROUP BY queries anywhere, and how a "headcount
over time" report can group by department when no historical department-assignment data exists.

## Decisions

### A. CSV downloads live under `/admin/reports/*.csv`, not `/api/v1/reports/*.csv`

PRD §23.2 documents `GET /api/v1/reports/*.csv`. This phase instead ships them as plain web-layer
downloads (`web.AdminReportsController`, mirroring `web.FilesController#download`'s existing
`ResponseEntity<byte[]>` + `Content-Disposition: attachment` shape): session-cookie
authenticated, Admin-only, reached only from a link on the Admin Reports page. There is no
external consumer of a versioned `/api/v1` contract for reports today, and PRD §23.1 itself notes
Bearer-token API auth is Phase 2 — building the OpenAPI-documented REST shape now would be
speculative. Revisit if an external system (e.g. Finance's own tooling) needs to pull these
programmatically.

### B. Aggregation lives in the owning module, not in `reports`

`reports.ReportService` never computes a SUM, COUNT, or GROUP BY itself. Each aggregation is a new
query on the module that owns the underlying entity, exposed through that module's facade:

- `timeoff.LeaveRequestRepository#summarizeUtilization` — the codebase's first GROUP BY query
  (per-employee-per-leave-type SUM of `durationDays` and COUNT of requests for APPROVED requests
  starting in a date range), exposed via `TimeoffFacade#summarizeUtilization`.
- `people.EmployeeStatusHistoryRepository#countActiveByDepartmentAsOf` — a GROUP BY over a
  point-in-time reconstruction (active employees as of one date, grouped by current department),
  exposed via `PeopleFacade#countActiveEmployeesByMonth` (called once per month in the requested
  range).

`reports.ReportService` only joins already-aggregated facade output against
`PeopleFacade#listEmployeesForReport` by employee id, in Java. This preserves CLAUDE.md §4's
facade boundary (no cross-module direct DB access) and keeps `reports` a thin composer rather than
a place that reaches into another module's repositories to aggregate on their behalf. Both new
aggregation queries are confined to their `*Repository` interfaces per
`ArchitectureTest.nativeQueriesAndJdbc_areConfinedToRepositories`'s existing rule.

### C. Headcount groups historical months by an employee's *current* department

No entity tracks historical department assignment — only `people.EmployeeManagerHistory` exists,
and it covers manager reassignment, not department. `PeopleFacade#countActiveEmployeesByMonth`
therefore groups every month's active-employee count (from `EmployeeStatusHistory`, which does
carry historical status/employment-type) by each employee's department *as of today*, not as of
that historical month. A department reorganization will show up as if it always applied.
Documented simplification, not a bug — revisit only if a real need for historical department
tracking surfaces (the same posture ADR 0017 already took for audit diffs not capturing
relationship-field changes).

### D. No pagination on report preview tables

Unlike `admin/notifications` and `admin/audit-log`, the three report preview tables render their
full result set on one page, no prev/next pagination. Row counts are bounded by employee count ×
leave-type count (Balance/Utilization) or month count × department count (Headcount) — at the first customer's
and the pilot tenants' scale (tens to low hundreds of employees), this stays well within a
single-page render. Revisit if a real tenant's row count makes that untrue (CLAUDE.md §11: no
optimization ahead of a benchmark showing a problem).
