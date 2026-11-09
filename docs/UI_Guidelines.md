# Caderly — UI Guidelines

**Version:** 1.0
**Scope:** All server-rendered pages in the Caderly web app (Thymeleaf + htmx + Alpine.js + Bootstrap 5).

The goal is a consistent, calm, professional HR product that feels closer to Linear/Notion polish than to legacy enterprise HR. When in doubt, choose the simpler visual choice — HR software is used by people who don't want to be looking at it.

Grounded in the TalentHR screenshots the PRD was derived from, but Caderly should look sharper: tighter type scale, more consistent spacing, better empty states.

---

## 1. Layout shell

**Fixed top bar (56 px):** logo left · global search center · primary CTA ("Book time off") · user avatar + menu right.

**Left sidebar (240 px, collapsible to 64 px on <lg):** primary navigation with icon + label. Items:
- Home
- People
- My Profile
- Files
- Calendar
- For Action
- Admin (only role=ADMIN)
- Reports (only role=ADMIN)

Active nav item: `--bs-primary-bg-subtle` tint background + `--bs-primary` left border (3 px) + `--bs-primary-text-emphasis` text + 600 weight.
Inactive: default text color, hover raises background to `--bs-tertiary-bg`. Every nav-link carries a transparent 3 px left border at baseline so activating one doesn't shift layout.

**Content area:** `max-width: none` (use full width) with `--bs-body-bg` background, page-scoped `container-fluid` with `py-4 px-4`. First element is always a page header (`<h1>` + optional right-aligned actions).

**Mobile (<md):** sidebar collapses into an offcanvas triggered from a hamburger in the top bar. Content becomes single-column.

---

## 2. Color palette

One layer: **system colors** (Bootstrap 5 semantic), all fixed application constants. There is no
per-tenant brand color (ADR 0016) — every tenant gets the same Caderly Petrol primary, in the app UI
and in transactional email alike. A tenant's only visual customization is its logo (§12).

### System (defaults)
| Token | Value | Use |
|---|---|---|
| `--bs-primary` | `#0F5568` | Primary buttons, links, active nav, focus rings |
| `--bs-primary-rgb` | `15, 85, 104` | rgba() derivatives of primary |
| `--bs-primary-text-emphasis` | `color-mix(in oklab, var(--bs-primary) 75%, black)` | Text on primary-subtle backgrounds (Admin badge, sidebar active) |
| `--bs-primary-bg-subtle` | `#E3EFF2` | Tinted backgrounds (Admin badge, sidebar active) |
| `--bs-primary-border-subtle` | `#AFCFD7` | Borders on primary-tinted surfaces |
| `--bs-secondary` | `#6C757D` | Secondary buttons |
| `--bs-success` | `#1F6B45` | Approved chip, success toast |
| `--bs-warning` | `#9A5F00` | Pending chip, warning toast, orange CTA accent |
| `--bs-danger` | `#A62A22` | Rejected chip, delete buttons, error toast |
| `--bs-info` | `#0F5568` | Informational callouts |
| `--bs-body-bg` | `#f8f9fb` | Content area background |
| `--bs-body-color` | `#1f2328` | Body text |
| `--bs-border-color` | `#e5e7eb` | Default hairline borders, table dividers |
| `--bs-tertiary-bg` | `#f3f4f6` | Hover backgrounds (sidebar inactive hover, dropdown-item hover) |

`--bs-info` is aliased directly to `--bs-primary` because the Caderly design system defines no info-equivalent color — its verify/notice/halt triad is closed and independent of brand. Success/warning/danger were adopted as Caderly's verify/notice/halt as-is (no further tinting) since Caderly documents that triad as a stable, brand-independent status set with its own AA contrast verification.

Sidebar is **not** a solid dark color — it's `bg-white` with a `border-end`, per the fragment actually shipped (`fragments/sidebar.html`). Active item: `--bs-primary-bg-subtle` tint + `--bs-primary` 3 px left border + `--bs-primary-text-emphasis` text. Inactive hover: `--bs-tertiary-bg`. (An earlier version of this doc specified a solid dark-navy sidebar with a solid-fill active state; that was never built. This section now documents the light sidebar that exists.)

**Bootstrap 5.3 gotcha:** `-bg-subtle`, `-text-emphasis`, `-border-subtle`, and `-rgb` tokens (and `--bs-focus-ring-color`) are static hex values compiled into Bootstrap's CSS — they are *not* derived from `var(--bs-primary)` at runtime. Changing `--bs-primary` alone leaves the Admin badge, sidebar active state, and focus ring on stock Bootstrap blue. Always override the whole primary family together (see `static/css/theme-overrides.css`).

### Radius scale
| Token | Value |
|---|---|
| `--bs-border-radius-sm` | `0.375rem` — small controls (`btn-sm`, `form-control-sm`) |
| `--bs-border-radius` | `0.5rem` — base (buttons, inputs, cards, badges) |
| `--bs-border-radius-lg` | `0.75rem` — modal/offcanvas content |

### Elevation scale
| Token | Value | Use |
|---|---|---|
| `--bs-box-shadow-sm` | `0 1px 2px rgba(16,24,40,.06), 0 1px 3px rgba(16,24,40,.08)` | Cards (`shadow-sm`) |
| `--bs-box-shadow` | `0 4px 12px rgba(16,24,40,.08), 0 2px 4px rgba(16,24,40,.06)` | Dropdowns, modals |
| `--bs-box-shadow-lg` | `0 12px 32px rgba(16,24,40,.12), 0 4px 8px rgba(16,24,40,.06)` | Offcanvas (targeted override — Bootstrap ships no shadow var for it by default) |

### Tenant color (removed)
`tenant.primary_color` existed from sub-phase 1.1 but was never wired to anything and never held a
value other than a stale Tailwind-indigo default — no Java, CSS, or Thymeleaf code read it. Sub-phase
1.10 dropped the column outright rather than finally building the injection (ADR 0016): one brand
color for every tenant is simpler, was already true in practice everywhere except this dead column,
and matches `docs/design-system/guidelines/BRAND.source.md` §9.3's explicit recommendation.

### Do not
- Hardcode hex values in templates. Use `var(--bs-primary)` or Bootstrap utility classes. The one
  sanctioned exception is `templates/email/*.html`: mail clients do not support CSS custom
  properties, so `_layout.html` and `_components.html` hardcode the brand hex values as literal
  inline styles, sourced from the same `docs/design-system/tokens/colors.css` values
  `theme-overrides.css` reads for the app UI.
- Add more accent colors beyond what's in this table without discussion.
- Introduce dark mode in MVP — one theme, done well.
- Reintroduce a per-tenant color column or setting without a new ADR superseding ADR 0016.

---

## 3. Typography

- **Family:** `"IBM Plex Sans", -apple-system, "Segoe UI", Helvetica, Arial, sans-serif`. `SecurityConfig`'s CSP (PRD §19.6) deliberately locks `font-src`/`style-src` to `'self'` — "every asset is served from our own origin" — so a CDN webfont (e.g. Bunny Fonts) was ruled out. IBM Plex Sans is **self-hosted**, but as vendored static files under `static/fonts/` with `@font-face` rules in `caderly.css`, not via the `org.webjars.npm:fontsource__*` webjar mechanism used for Bootstrap/Bootstrap Icons/Alpine/Inter previously — `org.webjars.npm:fontsource__ibm-plex-sans` isn't published to Maven Central yet (WebJars only mirrors npm packages on-demand), so the woff2 files (fontsource's own build, latin subset) are committed directly instead. Revisit switching back to the webjar once it exists upstream. Only the `400`/`500`/`600` weight files are vendored — matching this section's "no 700+" rule. `-apple-system`/`Segoe UI` remain as fallbacks while the webfont loads or if it fails.
- **Scale:**
  - `h1`: 1.75 rem / 600 weight — page titles
  - `h2`: 1.5 rem / 600 — section headers within a page
  - `h3`: 1.25 rem / 600 — card / widget titles
  - `h4`: 1.0625 rem / 600 — sub-sections
  - `body`: 1 rem / 400 — default paragraph
  - `small`: 0.875 rem / 400 — helper text, table meta, labels
  - `micro`: 0.75 rem / 500 uppercase tracked — badge labels only
- **Line height:** 1.5 for body, 1.25 for headings.
- **Weight:** 400 default, 500 for emphasis, 600 for headings and interactive labels (nav, buttons). Do not use 700+.
- **Figures:** IBM Plex Mono (400/500, vendored beside Plex Sans, BRAND §6) via the `.font-figure` utility in `caderly.css` for balances, durations, tenure and report number columns: numbers a person compares down a column. Dates and timestamps stay in Plex Sans with tabular digits (`.font-date`), so a date beside a name reads as one line of text. Running text stays in Plex Sans.

---

## 4. Spacing scale

Use **Bootstrap spacing utilities exclusively** — no inline `style="margin:...`.

Prefer: `mb-2` (0.5rem), `mb-3` (1rem), `mb-4` (1.5rem), `mb-5` (3rem). Same scale for `mt-`, `p-`, `gap-`.

- Between form fields: `mb-3`.
- Between form sections: `mb-4`.
- Between page-level blocks: `mb-5`.
- Card body padding: default `p-3`, oversized cards `p-4`.
- Table cell padding: default; do not override without cause.

---

## 5. Iconography

- **Primary icon set:** Bootstrap Icons (`bi-*`), already in classpath via WebJar.
- **Empty states:** a Bootstrap Icon at `fs-1`, muted (see §7.1). No illustrations.
- **Leave-type icons:** each `LeaveType` has an icon name field (e.g., `bi-palm`, `bi-bandaid`, `bi-calendar-heart`). Rendered wherever the type appears, colored per `LeaveType.color`.
- **Sizing:** icons inline with text = `1em`, standalone icons in cards = `1.25rem`, empty-state icons = `fs-1`.

---

## 6. Component conventions

### Buttons
- **Primary action per screen:** exactly one `btn-primary`. Everything else `btn-outline-secondary` or `btn-link`.
- **Destructive:** `btn-danger` only inside confirmation modals; on list rows use `btn-outline-danger btn-sm`.
- **Cancel is always a button, never a link:** `btn-outline-secondary`, whether it dismisses a modal/offcanvas or navigates away (an `<a class="btn btn-outline-secondary">` for the latter). `btn-link` is for genuinely link-like actions (sortable column headers, "Forgot password?"), not for Cancel.
- **Icon-only:** always add `aria-label` and use `btn` + Bootstrap Icon child.
- **Sizes:** default for main CTAs, `btn-sm` in tables and toolbars, avoid `btn-lg`. Modal and offcanvas action bars (§ Modals, § Slide-over panels) use `btn-sm` for both buttons — these sit inside an already-compact panel, not the main page CTA.

### Links
Both `.btn-link` (sortable column headers, "Forgot password?") and plain in-content `<a>` (table row → detail links, empty-state "How-to guide" links) share one treatment, overridden in `theme-overrides.css`: 500 weight, no underline at rest, underline on hover/focus. Nav links, dropdown items, the navbar brand, and buttons are excluded — they keep their own component styling. Do not hand-roll a different link style; extend the shared selector in `theme-overrides.css` if a new case doesn't fit.

### Cards
- Use `card` for all widgets, list items with heavy content, dashboard tiles.
- Structure: header (title + optional actions on right) → body → optional footer. Header uses `card-title` (`h3`).
- Cards do not stack shadows. Bootstrap default `shadow-sm`. Never `shadow-lg`.

### Tables
- Wrapper `table-responsive` on every table.
- Use `table table-hover align-middle` classes always. Never `table-striped` (looks dated).
- Header cells: `<th scope="col">` in every case (accessibility).
- Empty table: render empty-state block, not an empty `<tbody>`.
- Row actions in a right-aligned column with `text-end`, use `btn-outline-secondary btn-sm` icon buttons.
- Sortable columns: header is a `btn-link` with an up/down chevron icon.

### Pagination
For a table too large to render in full (first shipped: `admin/notifications.html`'s delivery log,
sub-phase 1.10, ADR 0016).
- **Prev/next only** — `pagination pagination-sm`, no numbered page links and no jump-to-page. A
  delivery log (or any similarly time-ordered list) is read in order; there is nothing to jump to
  by page number.
- Page size 25, server-side (`Pageable`/`Page` from Spring Data). Filters and pagination are both
  plain `GET` query parameters (`?status=&from=&to=&page=`) so a filtered page is bookmarkable.
- Pair the control with a plain-text `Page N of M` beside it (`common.pagination.page-of`), not
  inside the `<nav>` itself.
- `.pagination` needs the same component-local CSS override every other Bootstrap 5.3 component in
  this app needs (§2's gotcha): Bootstrap compiles `$primary` into `--bs-pagination-color`/
  `--bs-pagination-active-bg`/etc. at build time, so untouched pagination renders stock Bootstrap
  blue. See `static/css/theme-overrides.css`'s `.pagination` block.

### Forms
- Every input has `<label>` linked by `for`/`id`. No placeholder-as-label.
- Required fields marked with `<span class="text-danger">*</span>` after the label.
- Use `form-floating` for compact forms (single-column), plain `form-label` above input for standard forms.
- Validation: server-side via Bean Validation. Render errors as `invalid-feedback` sibling of the input, with `is-invalid` on the input.
- Success feedback after submit: toast (see §7.4), not banner.
- Form actions bar at bottom: primary on right, "Cancel" as `btn-outline-secondary` on left, both `btn-sm` inside a modal/offcanvas (§6 Buttons). On mobile, stack full-width.
- Disable submit while htmx request is in flight (`hx-indicator` swaps a spinner).

### Modals
- Use for: booking leave, confirm delete, MFA prompt, invite user.
- `modal-lg` for booking (needs date picker), `modal` default otherwise.
- Never nest modals.
- Close on backdrop-click and Esc unless the modal has unsaved changes (guard via Alpine).

### Slide-over panels (Bootstrap offcanvas)
- Use for: editing a resource inline (edit department, edit leave type, edit employee section).
- Right-side, `offcanvas-end`, width ~480 px.
- Always has an explicit close X and a footer with Cancel (`btn-outline-secondary btn-sm`) + Save (`btn-primary btn-sm`).

### Tabs (profile pages)
- Use Bootstrap `nav-tabs` with `nav-link` items and `tab-pane` bodies.
- htmx `hx-get` swaps tab bodies (`hx-target="#tab-content"`) — do not render all tabs on initial page load.
- Active tab reflected in URL query param (`?tab=personal`) for shareability.

### Chips / status badges
Standard pill mapping — never invent new colors:

| Status | Bootstrap class | Icon |
|---|---|---|
| Pending | `badge bg-warning-subtle text-warning-emphasis` | `bi-clock` |
| Approved | `badge bg-success-subtle text-success-emphasis` | `bi-check-circle` |
| Rejected | `badge bg-danger-subtle text-danger-emphasis` | `bi-x-circle` |
| Cancelled | `badge bg-secondary-subtle text-secondary-emphasis` | `bi-slash-circle` |
| Employee role | `badge bg-dark text-white` | — |
| Manager role | `badge bg-info-subtle text-info-emphasis` | — |
| Admin role | `badge bg-primary-subtle text-primary-emphasis` | — |

Existing code in `people/list.html`, `people/profile.html`, `admin/users.html`, `admin/leave-types.html` predates this table and uses Bootstrap's stock solid `badge text-bg-{color}` helpers instead of the subtle/emphasis pair above. `theme-overrides.css` repoints `.badge.text-bg-*` to the matching subtle/emphasis colors via CSS override, so those existing badges already render with the soft-pill look this table specifies — no template edit needed. New code should still prefer writing `bg-*-subtle text-*-emphasis` directly per the table; both forms now look identical.

### Avatars
- Circle with initials fallback (first + last, uppercased) when no photo.
- Default background: hash of employee ID mapped to one of 8 muted colors (deterministic — same person always same color).
- Sizes: `24 px` in tables, `32 px` in nav, `40 px` in cards, `96 px` on profile.

---

### Charts (reports)
- **No chart library.** Charts are server-rendered from view models in `web.ReportCharts`: HTML
  bars, and an SVG line whose axis text is HTML so it never shrinks on phones. Markup lives in
  `fragments/charts.html`; tooltips are `caderly.js`'s `[data-chart-tip]` handler (hover and focus).
- **Categorical palette** (`--chart-1..6` in `caderly.css`): `#0C86AB`, `#E8703A`, `#6A5AD0`,
  `#24A37A`, `#B38A0E`, `#DB6A97`, validated on the white card (colour-blind separation, normal-vision
  floor, contrast). A series keeps its slot under any filter: slots follow the tenant's full,
  name-ordered list, and a 7th series or more folds into grey "Other" (`--chart-0`). Never reuse
  the status colours for a series, and re-validate before changing a hex.
- **A single series** (headcount) is brand petrol: 2px line, ~10% area wash, ringed end dot.
- **Marks and chrome:** bars at most 24px thick with a 4px rounded end and a 2px gap between stacked
  segments; hairline solid gridlines; text in ink tokens, figures in Plex Mono; a legend whenever
  there are two or more series; values only at bar ends and the line's end.
- **Accessibility:** the chart is `role="img"` with a one-sentence summary; the table below it
  stays the full data view; every mark is keyboard-focusable with the same tooltip text.

## 7. Data patterns

### 7.1 Empty states
Every collection view (People, Files, Documents, Time-off history, Tasks, Approvals, Reports) must have a designed empty state. Never show an empty table or a bare "No results."

Structure:
1. Centered Bootstrap Icon (`fs-1`, muted), the same pattern on every page. unDraw art was planned
   but never adopted; icons keep empty states consistent and cost nothing to maintain.
2. `h3` explanatory title ("No documents yet").
3. One-line supporting text ("Upload employment docs like contracts, IDs, certifications.").
4. Primary action button ("Upload document").
5. Optional secondary "How-to guide" link.

### 7.2 Loading states
- Full-page load: no skeleton needed (Thymeleaf is fast).
- htmx swap: show `hx-indicator` — a small spinner near the trigger element.
- Long-running actions (e.g., report export): disable the button, swap label to "Preparing…" with a spinner.

### 7.3 Error states
- Field-level: `is-invalid` + `invalid-feedback` per §6 Forms.
- Page-level (server error, network error): render inline `alert alert-danger` at the top of the affected component. Do NOT redirect to a generic error page mid-flow.
- Global 5xx: dedicated `error/5xx.html` with support contact + request ID.
- Global 404: dedicated `error/404.html` with search bar. (The search bar waits on global search; until then the page offers navigation instead of a control that would not work.)
- Global 403: dedicated `error/403.html` — "Access denied", worded so it does not confirm whether the page exists.
- **Every other status must land somewhere too.** Spring Boot resolves `templates/error/<status>.html` and then `templates/error/<series>xx.html`; anything matching neither falls through to Boot's Whitelabel page, which is unbranded and — with devtools active — prints a stack trace. `error/4xx.html` and `error/5xx.html` exist as the catch-alls that make this impossible. Add a status-specific page when the wording matters; never remove the catch-alls.
- Verify error pages by **looking at the rendered page**, not the status code. A correct 403 status with a Whitelabel body is a defect that status-only tests pass.

### 7.4 Success feedback
Toasts (Bootstrap `toast`) in the top-right, auto-dismiss after 4 s. Use `bg-success` for confirmations, `bg-info` for informational. Toasts are the ONLY confirmation mechanism for non-destructive actions — never use alerts or reload the page just to show success.

Destructive actions (delete, terminate): confirm via modal, then show a toast on success.

### 7.5 Confirmations for destructive actions
- Always show a confirmation modal.
- Modal title states the action ("Terminate Priya Sharma?").
- Body states the consequence ("This will revoke access immediately and cancel 2 pending leave requests. This cannot be undone.").
- Actions: `btn-outline-secondary btn-sm` Cancel (left), `btn-danger btn-sm` Confirm (right).
- Confirm button text should be the verb, never "OK" ("Terminate employee", "Delete file").

---

## 8. Domain-specific patterns

### 8.1 Book Time Off modal
- Trigger from top-bar CTA on any page (`hx-get="/leave/new"` → modal partial).
- Step 1: Leave type — chip row of `LeaveType`s with balance badge on each ("Vacation · 18/30").
- Step 2: Date range (single date picker component that supports range).
- Step 3: Half-day toggles for start (PM) and end (AM), only visible if type allows half-day.
- Step 4: Note textarea (optional).
- Live "This will use N working days" indicator updates on any change (htmx `hx-post="/leave/preview"`).
- Submit: disabled if balance insufficient, with the reason inline.

### 8.2 Home dashboard widgets
- Grid: 3 columns on `lg+`, 2 on `md`, 1 on `sm`.
- Every widget is a `card` with title in `card-header`, body content, optional link "View all" in `card-footer`.
- Widget loading: each widget htmx-loads independently on page load (`hx-get="/widgets/..." hx-trigger="load"`). This parallelizes.
- Never render more than 6 widgets by default.
- **Customize mode (per user):** a **Customize** button above the grid enters edit mode. Each card
  gains a drag handle, an eye toggle (hide/show) and up/down buttons — the buttons are the keyboard
  alternative to dragging (§9). A bar offers **Save**, **Cancel** (discard, reload) and **Reset**
  (default order, all visible). The layout is stored per user, not per tenant. Hidden widgets render
  as dimmed placeholders in edit mode and not at all otherwise; a placeholder never carries
  `hx-trigger="load"`, so a hidden widget costs no queries.

### 8.3 People list — view toggle
- Top-right button group: List · Grid · Org tree.
- Selected view persisted in `localStorage` per user, keyed by page.
- Grid view: 4 columns × N rows of avatar cards.
- Org tree view: horizontal tree rendered with an inline SVG helper — Phase 2.

### 8.4 Team Calendar
- Row per employee (avatar + name in first column, sticky).
- Columns = days of visible range, with weekend cells shaded slightly darker.
- Public holidays: rendered as a bar in every employee's row on the holiday's date, identical styling to a leave-type bar (no separate column shading, no `bg-warning-subtle`) — distinguished only by its bold `bi-calendar-heart-fill` icon; the holiday's name surfaces via the bar's hover tooltip, same as leave bars.
- Leave: rounded bar spanning start-to-end, same fixed brand-tinted background/border for every leave type, distinguished only by its bold `bi-*-fill` icon (centered — bars are icon-only, no name text).
- Half-day: bar occupies 50% of the cell (top or bottom).
- Hover any bar → Bootstrap `popover` with type, dates, duration.

### 8.5 Profile page
- Left column (sticky on scroll, 280 px): avatar with edit pencil for self, name, role badge, tenure, department icon+label, manager section with mini-avatar, "My peers" avatar cluster, contact card.
- Right column: tabs (Personal · Education · Job · Documents · Tasks · Time off).
- Tab content htmx-loaded on click, URL updated.

### 8.6 Approval inbox (For Action)
- Two Bootstrap pills: Tasks · Time off requests.
- Time off pane: sub-tabs Pending (n) · Completed (n).
- Row layout: LeaveType icon · employee mini-avatar + name · dates + duration · submitted-on · note (truncated with expand) · Approve/Reject buttons.
- Approve → toast on success, row removed via htmx.
- Reject → modal for optional reason → toast → row removed.

### 8.7 File upload (Files page & Documents tab)
- Upload control is a **dropzone**, not a bare native file input: a dashed-border card region (`.upload-dropzone`) containing a centered upload icon (`bi-cloud-upload`, `fs-1 text-muted`), a bold instruction line ("Drag and drop a file here, or click to browse"), and a muted caption line naming the allowed types and size limit — same weight/size vocabulary as this app's empty states (§7.1), so the two read as one visual family.
- The real `<input type="file">` is visually hidden (`visually-hidden`, never `display:none` — it must stay in the tab order and screen-reader-visible) inside a `<label for="...">` that wraps the whole dropzone, so the entire box is clickable with no extra JS to open the file picker.
- Drag-over state (`.upload-dropzone-active`, Alpine-driven) swaps the border/background to `var(--bs-primary)` / `var(--bs-primary-bg-subtle)` — the same re-themeable variables used everywhere else (§12), never a new hardcoded color.
- Fixed height regardless of state (empty / file-chosen / drag-active) — only border, background, and the selected-filename caption change, so the layout doesn't jump.
- Sits in its own `card` above the list/table, not squeezed into the page's header row — Admin-only pages (Files) gate the whole card with `sec:authorize`; self-or-Admin pages (Documents tab) gate it with the equivalent `th:if`.
- Upload button keeps the page's existing outline style (§6 Buttons) — this is not the page's one `btn-primary` action.

---

## 9. Accessibility (WCAG 2.1 AA target)

Non-negotiable in every screen:

- All interactive elements reachable by Tab in logical order.
- Visible focus ring on every focusable element (Bootstrap default 3 px `--bs-primary` outline).
- Every `<img>` and standalone icon has `alt` or `aria-label`. Decorative icons: `aria-hidden="true"`.
- Every form control has an associated `<label>`.
- Color is never the only signal — always paired with icon or text (e.g., Approved chip has check icon + green + word "Approved").
- Contrast: text against background ≥ 4.5:1; large text (18 pt+ or 14 pt bold) ≥ 3:1. Enforce with axe-core in CI.
- Skip-to-content link at top of layout (visible on focus).
- Modals trap focus and restore it to the trigger on close.
- Toasts announced via `role="status"` + `aria-live="polite"`.
- Errors announced via `role="alert"`.

**Testing:** every merged PR runs axe-core on every page in E2E. Any Serious or Critical issue blocks merge.

---

## 10. Responsive breakpoints

Use Bootstrap defaults exclusively:

| Breakpoint | Min width | Behavior |
|---|---|---|
| `xs` | <576 px | Mobile — single column, sidebar as offcanvas |
| `sm` | ≥576 px | Small mobile — same as xs |
| `md` | ≥768 px | Tablet — sidebar visible collapsed, 2-col widgets |
| `lg` | ≥992 px | Desktop — full sidebar, 3-col widgets |
| `xl` | ≥1200 px | Large desktop |
| `xxl` | ≥1400 px | Extra large — content max-width caps at 1400 px, centered |

Design mobile-first for Employee-facing pages (profile, book time off, calendar). Admin pages can assume desktop.

---

## 11. Date, time, number formatting

- **All dates displayed in the tenant's timezone.** Store UTC, convert on render via a Thymeleaf utility (`#temporals.format(...)`).
- **Date format default:** `MMM dd, yyyy` (e.g., "Feb 01, 2019"). Configurable per tenant in Phase 2.
- **Time format default:** `HH:mm` (24-hour). Configurable per tenant later.
- **Duration:** "1 day" / "3 days" / "3.5 days" — never "3 d" or "3d".
- **Tenure:** `NY, MM, DD` on profile ("7Y 5M 20D") — matches TalentHR pattern.
- **Currency:** `#numbers.formatCurrency(amount, 'AED', locale)` with tenant locale.
- **Percentages:** whole numbers unless <1% (then one decimal). Always with `%` symbol adjacent, no space.

---

## 12. Tenant branding hooks

One thing varies per tenant: the **logo**, uploaded by the Super Admin as a PNG or JPEG (ADR 0020)
and served same-origin from `/tenant-logo`. It is a guest on the Caderly brand, not a replacement:

- **Top bar:** Caderly mark + "Caderly", a thin divider, then the tenant logo as an `<img>`, height
  auto-sized inside a 120 x 40 px box (`max-width` 120, `max-height` 40, 96 px wide on phones), `object-fit: contain`, alt text = tenant name. It replaces only the
  tenant *name* text.
- **Auth pages** (login, forgot/reset password, accept invite): the logo is the `<h1>` content,
  auto-sized inside a 200 x 60 px box, centered; the alt text is the tenant name.
- **Fallback:** with no uploaded logo, the tenant name renders as text in both places (the fallback is
  designed, not tolerated — it is what most tenants show for a long time).
- The same logo-or-wordmark rule applies inside `templates/email/_layout.html`'s header (20 px tall), using the
  absolute `/tenant-logo` address on the tenant's subdomain (ADR 0020).

There is no per-tenant primary color (ADR 0016, sub-phase 1.10) — one Caderly brand color for every
tenant, in the app and in email. Nothing else varies per tenant: no per-tenant font, layout, or icon
swap.

---

## 13. htmx conventions

- Every htmx-triggered request has a URL that also works via GET on its own (RESTful bookmarkability where meaningful).
- `hx-target` always references an ID on the same page. Never `hx-target="body"`.
- `hx-swap="outerHTML"` for replacing components; `hx-swap="innerHTML"` for filling containers.
- `hx-boost="true"` on the layout `<body>` for progressive links — falls back to full page navigation with JS off.
- All state-changing actions include CSRF token via `hx-headers` (configured globally in a `<meta>` tag reader).
- Error handling: attach `hx-on:htmx:responseError` on triggers OR use a global `htmx.on('htmx:responseError', ...)` handler that shows a toast.
- No inline JavaScript in templates. Put JS in `caderly.js` or Alpine components.

---

## 14. Alpine.js conventions

Use Alpine only for **client-only interactivity** with no server round-trip. Examples: sidebar collapse, modal open/close, tab switching (before the tab content is fetched), form validation preview, dropdown menus.

- Prefer `x-data="{}"` inline for one-off components.
- Extract to a component in `caderly-alpine.js` if reused across three or more templates.
- Never store business data in Alpine — use htmx to sync with server.
- No Alpine plugins in MVP (no `intersect`, `mask`, etc.) — keeps bundle size trivial.

---

## 15. What NOT to do

- Do not use jQuery. htmx + Alpine covers everything jQuery did.
- Do not import a UI kit beyond Bootstrap 5 (no Bootstrap templates like AdminLTE, no shadcn ports).
- Do not create custom color variables outside §2. Always use Bootstrap semantic colors.
- Do not use Font Awesome or Material Icons. Bootstrap Icons only.
- Do not build custom form controls when a Bootstrap one exists (`form-select`, `form-check`, etc.).
- Do not use Bootstrap tooltips for critical info — they don't work on touch. Use popovers only on hover-only affordances.
- Do not autoplay animations. Prefer subtle transitions (0.15 s ease) only on hover/focus.
- Do not add fixed footers.
- Do not use browser `alert()` / `confirm()` / `prompt()`. Ever.

---

## 16. When adding a new UI

Before writing any HTML, answer:

1. Which section of PRD §24 does this correspond to?
2. Which Bootstrap components will I use (and only Bootstrap)?
3. Am I introducing any new color, spacing, or type-scale value? If so, stop and update this document first.
4. What's the empty state, loading state, and error state?
5. Is it accessible via keyboard alone?
6. What htmx interactions replace what would otherwise be JavaScript?
7. Are all user-facing strings externalized as `#{...}` message keys, not hardcoded? (ADR 0013 — see CLAUDE.md §7 for the key naming convention.)

If you can't answer these in one line each, don't write the template yet.

---

## 17. Public site (base domain)

The marketing site, sign-up and legal pages on `caderly.app` / `www.caderly.app` (ADR 0022) are a
deliberate exception to §1–§14: they are a brochure, not an app screen, and they follow the
structure of a conventional SaaS landing page rather than the sidebar/topbar shell.

- **Layout:** `templates/site-layout.html` only. Never mix the tenant `layout.html` (sidebar,
  topbar, Bootstrap JS) into a site page, or site styles into an app page.
- **Styles:** `static/css/site.css`, standalone, no Bootstrap. It reuses the Caderly Design System
  tokens (brand teal, ink scale, IBM Plex Sans for text, IBM Plex Mono for figures, IBM Plex Serif
  300 Italic only for the brand's own sentences: the hero line and the footer tagline) declared at its
  `:root`. New colours or type sizes go into those tokens first, same rule as §16 question 3.
- **Script:** `static/js/site.js`, plain DOM, progressive enhancement only — every form works with
  JavaScript off. No htmx or Alpine on the site. No inline script or `on*` attributes (CSP
  `script-src 'self'`).
- **Images:** `static/img/site/*.webp`, real photography; explicit `width`/`height` to avoid layout
  shift, `loading="lazy"` below the fold.
- **Copy:** only features that are built. Strings via `#{site.*}`; the legal texts are the one
  exception (ADR 0022 decision G).
- **Motion:** decorative animations are wrapped in `prefers-reduced-motion: no-preference`.

---

*End of UI Guidelines — v1.0*
