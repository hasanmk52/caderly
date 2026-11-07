# CLAUDE.md — Caderly Codebase Brief

You are working on **Caderly**, a multi-tenant HRIS + Leave Management SaaS built by a solo engineer. This file is your standing brief. Follow it every session. When in doubt, ask before deviating.

---

## 1. Product one-liner

Multi-tenant SaaS replacing TalentHR's free plan for a first customer (customer #1) and 2-5 friendly pilot companies. Region-agnostic: every tenant configures its own leave types, weekend days, holiday calendar, and org units.

---

## 2. Where to find things

- **Product spec:** `docs/Caderly_PRD.md` — 29 sections. Cite the section number when quoting.
- **Delivery plan:** `docs/Caderly_Implementation_Plan.md` — phased sub-tasks with DoD.
- **UI guidelines:** `docs/UI_Guidelines.md` — layout, color, typography, component conventions, htmx/Alpine rules. Consult before writing any Thymeleaf template. Any deviation requires updating this document first.
- **Current sub-phase:** `docs/CURRENT_PHASE.md` — a local working file (git-ignored, not in the repository); read it at the start of every session.
- **Architecture Decision Records:** `docs/adr/NNNN-<slug>.md` — one per significant choice. Add an ADR whenever you make a non-obvious design decision.
- **Deployment:** `INSTALL.md`, `Dockerfile`, `docker-compose.yml`, `deploy/` — see §3 and ADR 0023. The detailed runbook is kept privately, not in the repo.

**Rule:** At the start of every session, `Read` `docs/CURRENT_PHASE.md` and the specific PRD + Implementation Plan sections it references. Do not skim. Do not assume — read.

---

## 3. Locked stack (do not change without an ADR)

- **Java 25** (LTS), configured in `pom.xml` (`<java.version>25</java.version>`)
- **Spring Boot 4.1.x** on Spring Framework 7
- **Maven** with the Maven Wrapper (`./mvnw`)
- **PostgreSQL 17**
- **Flyway** (forward-only migrations). Never edit an already-applied migration file for any reason, including a comment-only fix — Flyway checksums the whole file, and any edit breaks `flyway validate` against every environment (local dev, CI, prod) that has already run it. A comment error in a shipped migration stays wrong; fix it in a new migration's own comment or a doc, not by touching the file in place.
- **Hibernate ORM 7.x + Spring Data JPA** (version tracks the Spring Boot BOM — check `./mvnw dependency:tree` rather than assuming a number here)
- **Spring Security 7** (session cookies, CSRF on, BCrypt cost 12, TOTP MFA planned) — version tracks the Spring Boot BOM; 7.1.0 as of Boot 4.1.0. Security 7 removed most API deprecated across the 6.x line, so 6.x configuration guidance will not compile.
- **Thymeleaf + htmx + Alpine.js + Bootstrap 5** — server-rendered, no SPA
- **Caffeine** (in-JVM cache, no Redis at current scale)
- **Testcontainers Postgres** + JUnit 5 + AssertJ + Mockito
- **ArchUnit** for architecture tests
- **Playwright-Java** for E2E
- **springdoc-openapi** for API docs
- **JaCoCo** for coverage
- **Docker + docker-compose** for deployment of the app + reverse proxy only; **PostgreSQL is external** (host-native install locally and in the first customer's production, managed service in cloud tenants). Distroless JRE 25 base image. Reverse proxy is **Caddy** (wildcard TLS via the DNS-01 challenge); the containers use host networking (ADR 0023).
- **Testcontainers** in tests uses its own ephemeral Postgres via Docker — unrelated to dev/prod Postgres.

**Never suggest** React, Redis, Kafka, MongoDB, microservices, JWT-in-localStorage, or any dependency the PRD did not authorize. Push back and cite §20 or §7 if pressed.

---

## 4. Package structure (mirror this exactly)

```
com.caderly.caderlyhr
├── identity        # AppUser, roles, tokens, MFA
├── tenant          # Tenant, TenantContext, TenantResolutionFilter
├── org             # Division, Department
├── people          # Employee + sub-entities
├── timeoff         # LeaveType, LeaveBalance, LeaveRequest, PublicHoliday
├── calendar        # CalendarService (team grid), CalendarTokenService, CalendarFeedService (iCal)
├── documents       # CompanyFile, EmployeeDocument
├── notifications   # EmailOutbox, EmailTemplate
├── audit           # AuditEntry, LoginAudit, AuditListener
├── reports         # Report services
├── storage         # FileStorage interface + Local + S3 impls
├── security        # SecurityConfig, TotpService
├── web             # Thymeleaf controllers (server-rendered), incl. the public site (@PublicSiteController)
├── api             # REST controllers under /api/v1
├── superadmin      # Cross-tenant console (separate security realm)
├── signup          # Self-serve workspace sign-up: PendingSignup, SignupService (ADR 0022)
├── bootstrap       # Dev-profile data seeding (DevDataSeeder)
└── common          # BaseEntity, TenantAwareEntity, exceptions, utils
```

**Rules:**
- No cross-package direct DB access. Cross-module reads go through a `<Module>Facade` interface exposed by the owning module.
- No package cycles. Enforced by ArchUnit.

---

## 5. Multi-tenancy contract (non-negotiable)

Every violation is a critical bug. Assume nothing.

1. Every tenant-scoped entity **must** extend `com.caderly.caderlyhr.common.TenantAwareEntity` (which has `tenant_id UUID NOT NULL`).
2. Every tenant-scoped table **must** have Postgres RLS enabled with policy `USING (tenant_id::text = current_setting('app.tenant_id', true))`.
3. `TenantContext` is a `ThreadLocal<UUID>` populated by `TenantResolutionFilter` from the subdomain and cleared in `finally`.
4. Every tenant-scoped entity's `tenant_id` field is annotated `@TenantId` (Hibernate 7 discriminator multi-tenancy). Hibernate arms this filter itself, from `TenantIdentifierResolver` (a `CurrentTenantIdentifierResolver` bean backed by `TenantContext`), on every query it generates for that entity — including `find(id)`, unlike a hand-written `@Filter`. No manual `WHERE tenant_id = ?` in JPQL, ever.
4a. The Postgres RLS session variable (`app.tenant_id`) is set at the start of every transaction by `TenantSessionVariableListener`, a Spring `TransactionExecutionListener` registered on the transaction manager — not an AOP aspect. This is the defense-in-depth backstop from PRD §20.4; it runs regardless of which bean or annotation started the transaction.
5. Every write auto-sets `tenant_id` via Hibernate's `@TenantId` generation (`TenantIdGeneration`), driven by the same resolver as rule 4 — never a hand-written listener.
6. Any code that needs to bypass tenancy (Super Admin, system jobs) must do so via `TenantContext.runAsSystem(() -> ...)` and audit the action.
7. **Every new entity must have an ArchUnit test proving it extends `TenantAwareEntity`.** Add it in the same PR.
8. **Every service method must have an integration test proving cross-tenant reads return empty.** Two-tenant fixture in `TenantIsolationTestBase`.

If Claude is about to write a query, controller, or repository method: stop and confirm all of the above are enforced by construction, not by review.

---

## 6. Security contract (OWASP-aligned, non-negotiable)

Maps to OWASP Top 10 (2021 edition). Every rule is enforced by code + test, not documentation.

### A01 Broken Access Control
- Every controller method annotated with `@PreAuthorize("hasRole('...')")` or `@PreAuthorize("hasAnyRole(...)")`. No unannotated methods allowed — `SecurityConfig` denies by default.
- Row-level access checks on top of tenant filter: employee can only edit own profile; manager can only see reports; owner check enforced in service, not controller.
- Test: for every endpoint, one test per role asserting 200 or 403 as expected.

### A02 Cryptographic Failures
- BCrypt cost 12 for passwords; never MD5, SHA-1, or plain SHA-256 for passwords.
- Reset/invite tokens: 32-byte `SecureRandom`, stored **hashed** (SHA-256) in DB.
- Column encryption via `CryptoConverter` for: `government_id.id_number`, `bank_detail.*`, optionally `employee.base_compensation`. Algorithm: AES-256-GCM. Key from env var; never hardcoded.
- TLS 1.2+ only; HSTS with 1-year max-age + preload.

### A03 Injection
- **Zero string concatenation in SQL/JPQL.** Only parameterized queries or JPA criteria. Grep test: fail CI if `entityManager.createQuery(".*" + ...` appears.
- Input validated with Bean Validation (`@Valid` + `jakarta.validation` annotations) on every DTO.
- File upload: MIME + magic-byte check (Apache Tika) + extension whitelist + size limit. No exec-able types.

### A04 Insecure Design
- Threat-model any new state-changing feature in the ADR before implementing.
- No self-approval (employee cannot approve own leave, even as Manager/Admin — PRD BR-6).
- Rate-limit with Bucket4j (`RateLimitFilter`): login 10/min/IP (Super Admin login on its own counter), password-reset request 3/hour/email, sign-up per IP and per email, and the sign-up address check. A general per-session API limit is not implemented.

### A05 Security Misconfiguration
- CSRF enabled on all state-changing forms (Spring Security default).
- Security headers via `SecurityHeadersFilter`: CSP, X-Frame-Options: DENY, X-Content-Type-Options: nosniff, Referrer-Policy: strict-origin-when-cross-origin, Permissions-Policy minimal.
- Actuator exposure is pinned to `health,prometheus` (`management.endpoints.web.exposure.include`), so `env`, `heapdump` and the rest do not exist. In deployment the whole actuator moves to a loopback-only management port that the proxy never forwards (`docker-compose.yml`); `SecurityConfig.prometheusOnManagementPort` allows the scrape path only on that port.
- No default passwords in `application.yml` — everything from env vars.
- Directory listing off. Server banner hidden.
- **Error responses never carry internals.** `spring.web.error.include-stacktrace`, `include-exception`, `include-message` and `include-binding-errors` are all pinned to `never`/`false` in `application.yml`. Pinned, not left to Boot's defaults: `spring-boot-devtools` raises all four to `ALWAYS`, which is how a 403 once returned the whole filter chain to the client. Note these moved from `server.error.*` in Boot 4.0.0 and the old keys are ignored silently — `ErrorPageResolutionTest` asserts both the bound value and the namespace.
- **Every error status renders a Caderly page.** `templates/error/` carries `403`, `404`, `4xx` and `5xx`; the series catch-alls are what keep a new status from falling through to Boot's Whitelabel page. An RBAC test that only asserts the status code will not notice — assert the rendered page too.

### A06 Vulnerable and Outdated Components
- Intended controls, **not enabled yet** (do not describe them as in place): Dependabot on GitHub, and a Trivy scan of the built Docker image in CI that blocks merge on HIGH/CRITICAL.

### A07 Identification and Authentication Failures
- Session cookies: `HttpOnly`, `Secure`, `SameSite=Lax`; 8 h idle timeout plus a 24 h absolute cap (`AbsoluteSessionTimeoutFilter`).
- Account lockout after 5 failures in 15 min (per email + IP).
- MFA (TOTP) supported per-user, enforceable per-tenant for Admin role (planned; not built yet).
- Session revocation on password change and role change.

### A08 Software and Data Integrity Failures
- Only trusted Maven repos (Maven Central + Spring Milestone if needed).
- Maven Wrapper checksum verified.
- Docker base image pinned by digest, not just tag.
- No `eval` / dynamic classloading of user input.

### A09 Security Logging and Monitoring Failures
- Every write goes through `AuditListener` (see §7 of PRD).
- Every login attempt (success + failure) goes to `login_audit`.
- Structured JSON logs to stdout with request ID + tenant ID + actor ID in MDC.
- No secrets, tokens, or PII in logs. Verify with a grep test in CI over log format.

### A10 Server-Side Request Forgery
- No feature currently makes outbound HTTP based on user input. When added (webhooks in Phase 2), URL allowlist per tenant + block private IP ranges (10/8, 172.16/12, 192.168/16, 169.254/16, ::1, fc00::/7).

---

## 6a. Reliability contract — durable outbox for external side effects (non-negotiable)

Any code that causes an effect outside the local Postgres transaction — sending email, calling an HTTP webhook, publishing to a queue, writing to S3, calling a third-party API — must go through a durable outbox pattern:

1. **Write an intent row** (`email_outbox`, `webhook_outbox`, `push_outbox`, etc.) *in the same transaction* as the business action that triggered it. No `mailSender.send()` / `restClient.post()` / `s3Client.putObject()` inline in a request path.
2. **A `@Scheduled` dispatcher** polls the outbox (`fixedDelay = 30s` is the default; tune per outbox if needed), performs the external call, and marks `SENT` on success.
3. **On failure:** increment `attempts`, set `next_attempt_at` with exponential backoff (30s → 2m → 10m), retry up to 3 times, then mark `FAILED` with the last error and log at `error`. **Never lose the intent row.**
4. **Every outbox has an Admin UI** to inspect `FAILED` rows and retry them (may ship one sub-phase after the outbox itself, but must exist before the outbox goes to production).

**Exceptions:**
- **Read-only external calls** (fetching public data, no data-loss risk) may be inline.
- **Non-idempotent third-party calls** (payments, OTP SMS, anything that shouldn't be retried without deduplication) require an idempotency-key column on the outbox row plus deduplication by the receiving side. Discuss in an ADR before implementing.

**Never** use `@Async` for an unrecoverable side effect. `@Async` = in-memory queue = silent data loss on restart.

Currently in use: `email_outbox` (from sub-phase 1.2). Planned: `webhook_outbox` (Phase 2 Slack/MS Teams integrations), any future outbound HTTP.

---

## 7. Coding standards

- **Java 25 features to prefer:** records for DTOs and value objects, pattern matching in `switch`, `sealed` interfaces for state machines (e.g. `LeaveRequestStatus`), `var` for obvious locals only.
- **Style:** IntelliJ's default Java formatter (Reformat Code). Not enforced by a CLI/CI formatter — `.editorconfig` at the repo root pins the baseline (4-space indent, UTF-8, LF, final newline) so IntelliJ applies it consistently. There is no Spotless or other formatter in the build.
- **Static analysis:** PMD runs on `verify` in report-only mode (`failOnViolation=false`). SpotBugs is configured but not bound to the build, because its current release cannot parse Java 25 class files. There is no Checkstyle or ErrorProne. CI blocks merge on failing tests, not on static-analysis findings.
- **Null-safety:** use `Optional<T>` for return types that may be absent; never for parameters. Non-null by default: annotate the package with `@NullMarked` (JSpecify).
- **Exceptions:** custom `CaderlyException` hierarchy with `errorCode`; controller-level `@ExceptionHandler` maps to RFC 7807 Problem Details.
- **Transactions:** `@Transactional` on service methods, never on repositories or controllers. Read-only by default; annotate write methods `@Transactional`.
- **Logging:** SLF4J with `@Slf4j` from Lombok (avoid Lombok elsewhere — records + records builder cover most cases). Log level: `info` for state changes, `warn` for expected failures, `error` for unexpected; no `println`.
- **No Lombok `@Data`** — records for immutable, plain classes for entities. Only `@Slf4j` and `@Builder` (sparingly) are allowed.
- **DTOs are records**, entities are classes with private setters. Never expose entities across the web layer.
- **Fluent builders** for test fixtures via `EmployeeFixture.aRandomEmployee()`.
- **Comment "why", not "what".** The code shows what. Durable citations only: ADR/PRD/BR/§ references are fine and expected; internal roadmap pointers ("Phase 1.6", "sub-phase 1.9", "CURRENT_PHASE.md", "Task N", review finding IDs) are not — they read as current when written but go stale the moment the phase ships, and nothing then updates them. If a comment's only content is "this used to work differently" or "this was fixed/reverted/widened," either state the current constraint in present tense or delete it — don't narrate the edit history.
- **Document complex logic with concise "why" comments.** For tricky math (leave duration, working-day calculation), security-sensitive branches, state-machine transitions, non-obvious algorithm choices, workarounds for library or platform quirks, and any edge-case handling that isn't obvious from method + variable names, add a 1–3 line comment above the block explaining the reasoning or the constraint being satisfied. Straightforward code stays uncommented. Never restate what the code obviously does.
- **Dependency versions:** All third-party dependency versions declared in `<properties>` at the top of `pom.xml`, never inline in `<dependency>` blocks. Convention: property key = `<artifactId>.version` (e.g., `bootstrap.version`, `bucket4j.version`). This gives one place to answer "what version of X are we on?" and one line to bump. Do not add a new dependency without also adding its version property.
- **User-facing strings go through `messages.properties`, never a literal** (PRD §7 NFR, ADR 0013). Templates: `th:text="#{key}"` (dynamic key from a variable: `#{|prefix.${var}|}`). Bean Validation: `message = "{key}"` curly-brace syntax — Boot auto-wires the app's `MessageSource` into Hibernate Validator's interpolator. `CaderlyException` detail text: keyed by `"error." + errorCode` (lowercased, hyphenated) and resolved in `GlobalExceptionHandler`/`web.WebMessages`, falling back to the exception's own message when no key exists yet — do not add an args array to `CaderlyException` for this (see ADR 0013's non-decision). Key convention: `common.*` for strings shared across modules, `<module>.<page>.<element>` for page-specific ones. MVP ships English only (`spring.web.locale-resolver: FIXED`) — do not build a language switcher or session-based locale resolution ahead of a second locale actually existing.

---

## 8. Testing rules (block merge on failure)

- **Unit:** JUnit 5 + AssertJ + Mockito. Pure logic. Target ≥70% line coverage per module; JaCoCo reports it, the threshold is declared but not enforced by the build yet.
- **Integration:** `@SpringBootTest` + Testcontainers Postgres. Every service happy path + at least one sad path. Every controller: one 200 test + one 403 test per protected endpoint.
- **Architecture:** ArchUnit tests in `com.caderly.caderlyhr.architecture` package. Enforce:
  - Every `@Entity` in a tenant package extends `TenantAwareEntity`.
  - Every controller method has `@PreAuthorize`.
  - No cyclic package dependencies.
  - No direct JDBC or `EntityManager.createNativeQuery` outside repositories.
- **Tenant isolation:** Every write test uses `TenantIsolationTestBase` which seeds tenants A and B, and asserts the write only touched the current tenant.
- **E2E:** Playwright-Java, one happy path per user-facing module.
- **Security tests:** OWASP ZAP baseline in nightly CI (weekly at minimum) — not set up yet.
- **Test naming:** `methodUnderTest_condition_expectedResult` — e.g. `book_whenBalanceInsufficient_throwsInsufficientBalance`.

**Rule for Claude:** when implementing any feature, write the failing test first, then the code. Especially non-negotiable for: leave duration algorithm, tenant filter, access control decisions, audit writes.

---

## 9. Commit & PR rules

- **Branch naming:** `phase-1.1-multitenancy`, `phase-1.6-leave-approvals`. One branch per sub-phase.
- **Commits:** conventional-commits format (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `chore:`, `sec:`). Present tense, imperative.
- **Commit granularity:** one logical change per commit. `test:` and `feat:` may be separate commits.
- **PR checklist** (mandatory, in template):
  - [ ] All rules in §5 (tenancy) and §6 (security) enforced
  - [ ] Tests: unit + integration + tenant isolation
  - [ ] ArchUnit passes
  - [ ] Migration is additive (or has a documented rollback)
  - [ ] Audit log covers all writes
  - [ ] No secrets in code
  - [ ] PRD section that this implements: §_._
- **Deployment changes** (Dockerfile, compose, Caddyfile, `deploy/`, environment variables) must update `INSTALL.md` and, for a non-obvious decision, ADR 0023 or a new ADR.

---

## 10. Common task recipes

### Adding a new tenant-scoped entity
1. Extend `TenantAwareEntity`.
2. Add `@Entity` + JPA mapping. No public setters — use methods with intent (`markTerminated(date)`, not `setTerminationDate(date)`).
3. Add ArchUnit test asserting it extends `TenantAwareEntity`.
4. Write Flyway migration `V<timestamp>__add_<entity>.sql`. Add RLS policy.
5. Add repository extending `TenantAwareRepository<T, UUID>`.
6. Add service with `@Transactional` writes.
7. Add DTO (record).
8. Add controller with `@PreAuthorize`.
9. Write integration test + tenant isolation test + one E2E if user-facing.

### Adding a new REST endpoint
1. Confirm role in PRD §26 (permissions matrix).
2. Add controller method with `@PreAuthorize`.
3. Add `@Valid` on request body.
4. Add integration test per role (200 + 403).
5. Ensure OpenAPI annotations produce correct schema.

### Handling a state transition (leave request, employee lifecycle)
1. Model states as `sealed` interface or `enum`.
2. Transition rules in a `<Entity>StateMachine` class, unit-tested exhaustively.
3. Transitions logged to audit.
4. Optimistic locking on the aggregate (`@Version`).

### Adding new user-facing text (ADR 0013)
1. Pick a key following the convention in §7 — check `messages.properties` first for an existing `common.*` key before adding a new one (Cancel/Save/Delete/Actions and most field labels already exist).
2. Template: `th:text="#{key}"` (or `th:aria-label`/`th:placeholder` for non-body text). Never leave the English string as literal body content — the literal stays only as the tag's fallback preview text.
3. New `CaderlyException` throw site: reuse the existing `errorCode` for the key (`"error." + errorCode` lowercased/hyphenated) — don't invent a parallel key. Skip the key entirely (leave it on the exception's own fallback) if the message embeds a runtime value; `GlobalExceptionHandler` passes no `MessageSource` args today.
4. Add the key to `messages.properties`, English text only for now.
5. If the errorCode audit test (`architecture.ErrorCodesHaveMessagesTest`) fails, either add the key or add the errorCode to that test's `INTENTIONALLY_UNKEYED` set with a reason.

---

## 11. Anti-patterns — do not do these

- Adding `tenant_id` manually in a query. Use the filter.
- Skipping the ArchUnit test "just this once."
- Using `@SuppressWarnings` without a comment explaining why.
- Any `password` field in a log, DTO response, or exception message.
- Storing files in the database. Files go through `FileStorage`.
- Reaching into another module's entities directly. Go through the facade.
- Adding a new dependency without justification in the PR description.
- Writing generic wrappers "in case we need it later." YAGNI.
- Optimizing before there's a benchmark showing a problem.
- Assuming a hedge word means it's OK ("this should be tenant-safe"). Prove it with a test.

---

## 12. When to ask before proceeding

Stop and ask before:
- Any change to `SecurityConfig`, `TenantResolutionFilter`, `AuditListener`, `TenantIdentifierResolver`, or `TenantSessionVariableListener`.
- Adding a new Maven dependency.
- Any schema change that isn't additive.
- Any change to the leave duration algorithm.
- Any deviation from the PRD or Implementation Plan.
- Anything you cannot confidently defend against §5, §6, and §8.

---

## 13. When you start a session

1. `Read` `docs/CURRENT_PHASE.md`.
2. `Read` the specific PRD + Implementation Plan sections it names.
3. Restate the sub-phase's Definition of Done in your own words.
4. Produce a plan in plan mode. Wait for approval.
5. TDD-style: failing tests first, then code.
6. Before saying you're done: run the diff back and prove that §5 and §6 rules hold. Do not claim done if any test is skipped or any TODO remains.

---

## 14. Improvise
Refer to `docs/SELF_IMPROVE.md` and use any skills available to you whenever necessary.

---

*End of CLAUDE.md*
