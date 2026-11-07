# ADR 0022 — Public site on the base domain and self-serve tenant sign-up

**Status:** Accepted
**Date:** 2026-10-17
**Deciders:** Hasan (solo dev)
**Relates to:** ADR 0003/0004 (tenant isolation), ADR 0005 (system-scoped tables), ADR 0013
(messages), ADR 0016 (email templates), ADR 0019 (Super Admin realm), PRD §3.3, §8, §20.3,
FR-1.8, FR-12.2; CLAUDE.md §5, §6, §6a, §12

---

## Context

Until now the bare base domain (`caderly.app`, `localhost` in dev) returned 404 for everything
except `/superadmin/**`, and a tenant could only be created by the Super Admin (FR-1.8). PRD §3.3
listed "public marketing site + self-serve billing" as a v1 non-goal and §8 parked it in Phase 4.

The product owner has decided to bring the public site and **free** self-serve sign-up forward,
without billing. A company should be able to land on `caderly.app`, read what Caderly does, and
create its own workspace without Hasan touching the console. This ADR records how that fits into
the dual-realm security design (ADR 0019) and the tenancy contract (CLAUDE.md §5). The §12
ask-first changes to `SecurityConfig` and `TenantResolutionFilter` were approved before
implementation.

## Decisions

### A. The base domain is a third surface, recognised by host, not by path

`TenantResolutionFilter` treats exactly two hosts as the public site: the base domain itself and
`www.` + base domain. For those it sets no `TenantContext`, marks the request with
`PUBLIC_SITE_ATTRIBUTE`, and lets it through. Every other host keeps today's behaviour: one label
before the base domain resolves a tenant, unknown → 404, suspended → 503. `www` can therefore never
be a tenant slug (decision D reserves it).

Host, not path, because the two surfaces genuinely share paths: `/` is the tenant dashboard on
`acme.caderly.app` and the home page on `caderly.app`; `/login` is the tenant sign-in form on one
and the "find your workspace" page on the other. A path-prefix scheme (`/site/**`) would have given
the public site worse URLs to avoid a routing problem that host matching solves cleanly.

### B. A host-matched `SecurityFilterChain` for the public site

`SecurityConfig` gains a second chain at `@Order(50)` — after the Super Admin chain (`@Order(1)`,
`/superadmin/**`) and before the tenant chain (`@Order(100)`, everything else). Its
`securityMatcher` is "this request carries `PUBLIC_SITE_ATTRIBUTE`", so it never sees a tenant
subdomain request, and the tenant chain never sees a base-domain request.

- The site's routes and static assets are `permitAll()`; **everything else is `denyAll()` and
  answered as 404**, so `caderly.app/people` cannot reach a tenant controller through the back
  door. (Tenant controllers would refuse anyway — no `TenantContext`, no principal — but a route
  that isn't on the public site should simply not exist there.)
- No form login, no logout, no authentication provider: nobody signs in on the base domain. The
  Super Admin console keeps its own realm and session key (ADR 0019 decision C) untouched.
- CSRF stays on (CLAUDE.md §6 A05). A CSRF failure is answered 403, not 404: the path exists,
  the form is just stale. The sign-up form is the reason the base domain now creates a
  session at all — the CSRF token lives in it. Session cookies are host-only, so a base-domain
  session and a tenant session are separate cookies by construction.
- `SecurityHeaders.standard()` applies unchanged; the site ships no third-party script, style or
  font, so the CSP is not loosened.

Because Spring MVC cannot map `/` twice, base-domain controllers are marked `@PublicSiteController`
and a custom `RequestCondition` (registered through `WebMvcRegistrations`) restricts them to
requests carrying `PUBLIC_SITE_ATTRIBUTE`. It is attached through `getCustomMethodCondition`, not
`getCustomTypeCondition`: Spring only asks for a type condition when the class itself carries
`@RequestMapping`, and without the condition the two `GET /` mappings fail startup as ambiguous. Spring prefers a mapping with a custom condition over
one without, so `GET /` on the base domain lands on the site; on a tenant host the condition does
not match and only the dashboard mapping remains.

### C. Sign-up is verify-then-provision, through the existing provisioning service

1. `POST /signup` validates the form and stores a **`pending_signup`** row: company name, slug,
   time zone, weekend bitmask, admin name and email, a SHA-256 hash of a 32-byte token
   (`common.SecureToken`, the same scheme as invite/reset tokens — CLAUDE.md §6 A02), and an
   expiry 24 hours out. The verification email is queued in the **same transaction** through
   `EmailOutboxService` (CLAUDE.md §6a) as system mail (no tenant). **No tenant exists yet.**
2. `GET /signup/verify?token=…` only looks the link up and shows the company name, the address and
   a **Create workspace** button; it changes nothing. Business mailboxes (Microsoft Safe Links and
   similar scanners) open links before the person does, and a GET that spent the token would hand
   the real visitor an "expired" page. This is the same reason `/accept-invite` only consumes its
   token on POST.
3. `POST /signup/verify` (CSRF-protected) deletes the pending row (single use, by construction) and calls
   `TenantProvisioningService.provisionSelfServe(...)`, which runs the exact same
   create-tenant-then-invite sequence as the console (ADR 0019 decision B: the tenant row is
   written under `runAsSystem`, the first Admin under the new tenant's own `TenantContext`).
4. Instead of making the person wait for a second email, the POST lands on a same-site "Your
   workspace is ready" page whose button links to the new tenant's `/accept-invite?token=…`. It is a
   link rather than a redirect because the CSP's `form-action 'self'` also applies to redirects
   after a form post, and Chrome blocks one that crosses to the tenant's subdomain. The link reaches
   that page as a flash attribute, so a reload shows nothing and sends the visitor to sign in. The
   ordinary invite email is still queued as a fallback, so closing the tab loses nothing.
   Set-password and first sign-in are the existing accept-invite flow, unchanged.

The audit trail records the tenant creation with `actor_role = SELF_SIGNUP` and the pending row's
id as the actor id, mirroring the Super Admin's `SUPER_ADMIN` row from the console.

`pending_signup` is system infrastructure in the same category as `email_outbox` (ADR 0005
decision B): no `tenant_id`, no RLS, entity in `signup.system` so the ArchUnit tenant-entity rule
exempts it by package. It never holds employee data — only what the visitor typed into the form.

### D. Slug policy is shared and enforced twice

One `tenant.SlugPolicy` serves both the console's create form and sign-up: the existing format
(`^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$`), a minimum of 3 characters, and a reserved list (`www`,
`admin`, `api`, `app`, `mail`, `superadmin`, `caderly`, `support`, `status`, `login`, `signup`,
`help`, `docs`, `blog`, `static`, `assets`, …). Availability is checked against live tenants
**and** unexpired pending sign-ups, so an address is held for the 24 hours its link is valid. A
unique index on `pending_signup.slug` plus deletion of expired rows before insert closes the race
between two simultaneous sign-ups. The tenant table's own `UNIQUE (slug)` remains the final word
at provisioning time; if it ever fires, the visitor sees "that address was taken, start again".

Revealing whether a slug is free is inherent to letting people choose one. It reveals nothing a
DNS lookup of `slug.caderly.app` wouldn't. Whether an **email** address has an account anywhere is
never revealed (BRAND §3.3).

### E. Abuse controls

| Control | Where | Why |
| --- | --- | --- |
| Email verification before anything is created | decision C | A bot that never opens the link creates no tenant |
| `caderly.signup.enabled` kill switch (default **off**) | `SignupService`, site templates | Sign-up stays closed until the legal pages are reviewed; one flag closes it again if abused |
| 5 sign-ups per hour per IP, 3 per day per email | `RateLimitFilter`, same buckets mechanism as login | Stops the form being used to email-bomb a stranger |
| 60 address checks per minute per IP | `RateLimitFilter` | Bounds slug enumeration |
| Honeypot field | `SignupController` | Silently accepts and discards obvious bot posts |
| One pending sign-up per email | `SignupService` | A new request replaces the old one rather than holding many slugs |
| Expired rows deleted hourly | `PendingSignupCleanupJob` | Releases held slugs; no stale personal data |

A CAPTCHA was considered and deferred: it needs a third-party script (CSP change) and an outbound
verification call, and the controls above should be enough at this scale. A minimum-fill-time
check was also deferred: doing it properly needs a server-signed timestamp, which is more
machinery than the honeypot it would complement.

### F. A seat limit for self-serve workspaces

`tenant.seat_limit` (nullable `int`) caps the number of non-terminated employees. **NULL means
unlimited**, which is what every existing tenant and every console-created tenant gets.
Self-serve tenants are created with `caderly.signup.seat-limit` (50). `EmployeeService.create`
refuses the next hire with `EMPLOYEE_SEAT_LIMIT_REACHED` once the count reaches the limit; the
Super Admin can raise or clear it. Nullable rather than a default of 50 so that no existing tenant
is silently capped by a migration.

### G. Pages, strings and legal text

- Public pages use their own `site-layout.html` and `site.css` (brand tokens from
  `docs/design-system/tokens`), not the app shell: no sidebar, no Bootstrap dependency. Photos are
  self-hosted `.webp` files; IBM Plex Mono is self-hosted alongside Plex Sans.
- All UI strings go through `messages.properties` under `site.*` (ADR 0013).
- **Exception:** the Terms of Service, Privacy Policy and Data Processing Agreement are documents,
  not UI strings. They live as whole Thymeleaf templates (`templates/site/legal/*.html`) in
  English. A future locale gets its own translated documents reviewed by a lawyer, never a
  key-by-key translation. They render with a "draft — under review" banner until the review is done.
- The site's only JavaScript (`site.js`, served from `'self'`) fills in the address from the
  company name and calls the availability check. The form works fully without it.

### H. What is deliberately not built

- **"Email me my workspace links"** on the base-domain login page. It needs a lookup of one email
  across every tenant's `app_user`, which is exactly the cross-tenant read ADR 0019 decision B
  declined to introduce. The login page takes the workspace address and redirects to
  `<slug>.<base domain>/login` without checking whether it exists; an unknown address lands on the
  tenant 404 page, so nothing leaks.
- Billing, plans, trials. The plan is free; the seat limit is the only lever.
- Self-serve workspace deletion. Customers email support; the Super Admin's soft delete (30-day
  grace, FR-12.4) does the rest.

## Threat model (CLAUDE.md §6 A04)

| Threat | Mitigation |
| --- | --- |
| Mass tenant creation by bots | Verification before provisioning; rate limits; honeypot; kill switch |
| Email bombing a third party via the form | 3/day per address, 5/hour per IP; the email only says "someone entered this address" |
| Squatting many slugs | One pending row per email; 24 h expiry; hourly cleanup |
| Claiming an official-looking subdomain | Reserved list in `SlugPolicy`, shared with the console |
| Token theft from logs | Token never logged; stored only as SHA-256; single use; 24 h |
| Replay of a verify link | Row deleted before provisioning; second use shows "already used" |
| Base-domain request reaching tenant controllers | Public-site chain `denyAll()` → 404 for anything outside the site's routes; no `TenantContext` set |
| Cross-realm session confusion | No authentication on the public chain; cookies are host-only; Super Admin chain unchanged |
| Open redirect via the login page's address field | Redirect host is built only from a `SlugPolicy`-valid slug + configured base domain |
| CSRF on the sign-up form | Spring Security default CSRF on the public chain |

## Consequences

- PRD §3.3 and §8 change: the public site and free self-serve sign-up move from Phase 4 into
  Phase 1; billing stays in Phase 4.
- Deployment must serve the base domain and `www` alongside the wildcard (`*.caderly.app`): the
  deployment phase's Caddyfile and DNS guide include both.
- `TenantResolutionFilterTest#home_whenNoSubdomain_returns404` is replaced by tests asserting the
  base domain serves the public site and a tenant host does not.
