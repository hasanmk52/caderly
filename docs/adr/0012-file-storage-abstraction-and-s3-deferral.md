# ADR 0012 — File storage abstraction, S3 deferral, and upload validation shape

**Status:** Accepted
**Date:** 2026-08-26
**Deciders:** Hasan (solo dev)
**Relates to:** CLAUDE.md §3 (locked stack), §4 (package structure), §6 A03 (upload validation), §6a (durable-outbox pattern)

---

## Context

Phase 1.7 (`docs/CURRENT_PHASE.md`) adds Company Files (PRD §6.7 FR-7.1) and Employee Documents (PRD FR-3.9, §6.7 FR-7.2), both requiring PRD FR-7.5's storage-backend abstraction: "local filesystem or S3-compatible. Configured at deployment." Several design points needed pinning down before implementation.

## Decision A: `FileStorage` interface, `LocalFileStorage` only — S3 deferred

`storage.FileStorage` is a four-method interface (`store`, `open`, `delete`, and a `presignedUrl` default returning empty) implemented today only by `LocalFileStorage`, selected via `@ConditionalOnProperty(caderly.storage.backend=local, matchIfMissing=true)`.

**S3FileStorage is not built this phase.** The first customer's production runs local-filesystem storage; no cloud tenant exists yet requiring S3. Building it now would mean adding `software.amazon.awssdk:s3` plus a LocalStack (or MinIO) Testcontainers module purely to exercise a code path nothing uses — the "no generic wrapper in case we need it later" anti-pattern CLAUDE.md §11 names explicitly. The `presignedUrl()` seam is deliberately shaped so a future S3 implementation drops in without any caller (`documents`, `web`) changing: the download controller checks it and redirects when present, streams via `open()` otherwise.

This satisfies FR-7.5's *abstraction* requirement — the application code never assumes a filesystem — without satisfying its *second backend* in the same phase.

## Decision B: `caderly.storage.*` is the first `@ConfigurationProperties` class

Every prior tunable (`caderly.email.dispatcher.enabled`, `caderly.people.termination-job.enabled`, etc.) is a constructor-injected `@Value("${...}")`. `caderly.storage` introduces `StorageProperties`, a `record` bound via `@ConfigurationProperties` + `@EnableConfigurationProperties`, because it groups three related keys (`backend`, `local.root`, `max-file-size`) and needs `DataSize` parsing, which `@Value` cannot do on its own. `spring-boot-configuration-processor` was not added — no IDE metadata for these keys yet; revisit if `caderly.storage` grows further.

## Decision C: write ordering — bytes before the row, delete the row before the bytes

Upload: `FileStorage.store()` runs before `repository.save()`. A storage failure leaves nothing persisted. A save failure after a successful store leaves an orphan file — inert disk usage, harmless, and reconstructible from `storage_key`'s `{tenantId}/{uuid}` format if a cleanup sweep is ever built (not this phase).

Delete: `repository.delete()` is called before `storage.delete()`, **inside the same `@Transactional` method**. If the storage delete throws, the whole transaction — including the row delete — rolls back, so the row and its bytes either both survive or neither does. This is a deliberate refinement over "delete the row, commit, then delete the bytes": that ordering risks a *committed* row-deleted-but-file-still-present split whenever the file delete fails for a retryable reason (a permissions hiccup, a transient disk error), which is a worse user-facing outcome (a broken, doesn't-exist-but-should download) than the current shape's only failure mode — a genuine database commit failure after the method returns, which is a risk every write already carries regardless of file handling.

**CLAUDE.md §6a's durable-outbox pattern does not apply here.** A local filesystem write is not a call to an external system with its own availability/retry semantics — there is no third party to retry against, and a failed write surfaces immediately as a failed request the user retries themselves. Revisit this when `S3FileStorage` actually lands: a network call to a real external service is a different risk shape, and the outbox pattern becomes a live question worth its own decision at that point, not before.

## Decision D: two-tier size limit, servlet above app

```yaml
spring.servlet.multipart.max-file-size: 30MB   # hard backstop
caderly.storage.max-file-size: 25MB            # PRD FR-7.3, UploadValidator
```

The servlet limit sits *above* the business limit on purpose. A normal oversize upload (25–30MB) fails inside `documents.UploadValidator`, caught by the controller exactly like `timeoff.PublicHolidayService`'s CSV validation failures — a 200 response with an inline error banner, no servlet-level abort. Only a request exceeding the *servlet's* 30MB limit — a client bypassing the browser form entirely — reaches `GlobalExceptionHandler`'s new `MaxUploadSizeExceededException` handler, added as a backstop returning 413 with a rendered Caderly page rather than falling through to Boot's default error dispatch as a plain 500.

That handler is tested as a direct unit test of the handler method, not a full MockMvc round trip: empirically, MockMvc's default `MOCK` web environment never wires a real `MultipartConfigElement` onto the mock request, so `MockMultipartHttpServletRequestBuilder` bypasses container-level size enforcement regardless of the configured limit — confirmed by a test attempt that reached the controller body instead of throwing. Proving the servlet itself aborts an oversize request would need a `RANDOM_PORT` server and a real HTTP multipart client; not built this phase.

## Decision E: upload validation returns the detected type, not the declared one

`UploadValidator.validate(MultipartFile)` returns Tika's detected content type — verified against the pinned `tika-core` 3.2.3 jar (not assumed) that a `.docx`/`.xlsx`/`.pptx` with a matching extension resolves to its specific OOXML MIME string (not the generic `application/x-tika-ooxml`), because the filename hint specializes the zip-family magic match Tika's own `MimeTypes.applyHint()` produces. A disguised executable (`MZ` bytes named `payload.pdf`) is rejected because content and hint are unrelated in Tika's type hierarchy, so the hint cannot override the magic match — also verified empirically, not assumed. This detected type is what gets stored as `mime` on `CompanyFile`/`EmployeeDocument`, never the browser-declared `Content-Type` header, which is attacker-controlled.

## Decision F: `EmployeeDocument`'s two upload methods, never one with a role-defaulted parameter

`EmployeeDocumentService.uploadOwnAndList` takes no `visibility` parameter at all; `uploadOnBehalfAndList` (Admin-only, enforced by the caller) does. A single method defaulting `visibility` based on the caller's role would still bind a `visibility` field from the request body for every caller, including a self-uploader — meaning a non-admin employee could add `visibility=ADMIN_ONLY` to their own upload request and the server would need to remember to ignore it. Splitting the methods means there is no field to ignore in the first place on the self path.

Denied document access (wrong owner, wrong visibility, or a Manager who is neither) throws `NotFoundException`, matching `people.EmployeeService#requireOwnedBy`'s existing anti-enumeration convention, not `AccessDeniedException`.

## Consequences

**Positive:**
- FR-7.5's abstraction ships without the cost (two new heavy dependencies, a LocalStack Testcontainers module) of a second backend nothing uses yet.
- The Tika whitelist is verified against the actual pinned jar version, not documentation or memory — the headline "disguised executable rejected" security requirement is proven, not assumed.
- The self/on-behalf method split makes the visibility-tampering hole structurally impossible rather than relying on the controller remembering to ignore a field.

**Negative / open (known, accepted limitation):**
- `S3FileStorage` doesn't exist. The first cloud tenant requiring it needs a follow-up ADR revisiting whether the outbox pattern applies to it (Decision C).
- The `MaxUploadSizeExceededException` handler's servlet-level trigger is untested — only its own rendering logic is. A `RANDOM_PORT` + real-HTTP-client test would close this gap if it's ever worth the cost.
- Tika's OOXML detection has a soft spot noted during verification: a byte stream matching *no* magic pattern at all (not even a bare ZIP signature) renamed to `.docx` is classified by filename alone, with no content confirmation — weaker than PDF/PNG/JPEG's unambiguous magic prefixes. Not a practical risk (such a file isn't executable), but worth knowing if the DOCX/XLSX/PPTX branch of the whitelist is ever revisited.
- No orphan-file sweeper exists for the harmless-orphan cases Decision C accepts. Not needed at current upload volume; revisit with evidence of actual disk usage from orphans, not preemptively.

## Alternatives considered

**1. Build `S3FileStorage` + LocalStack now, per the Implementation Plan's literal text.** Rejected: two new dependencies for a backend with zero current consumers, and meaningful added test time, when the interface seam already makes adding it later a non-breaking change.

**2. `InMemoryFileStorage` for tests instead of writing to `target/test-storage`.** Considered, but `LocalFileStorage` itself needs a real-filesystem contract test regardless (path-traversal defense, actual directory creation), so a separate in-memory fake would duplicate the contract-test suite without removing the need for the filesystem-backed one. Rejected as extra surface for no coverage gain.

**3. A single `EmployeeDocumentService.upload(employeeId, file, visibility, callerIsAdmin)` method, with the controller passing `EMPLOYEE_PRIVATE` when the caller isn't admin.** Rejected (Decision F) — trusting the controller to always remember to override an attacker-supplied field is exactly the kind of "should be fine" CLAUDE.md §11 warns against proving with a test, not a convention.

## References

- PRD §6.7 (FR-7.1–7.5), FR-3.9, §21 (`company_file`/`employee_document` DDL sketch), §26 (permissions matrix)
- CLAUDE.md §3 (locked stack — no dependency without justification), §4 (package structure: `documents`, `storage`), §6 A03 (extension + magic-byte + size, all three), §6a (durable-outbox scope), §11 (anti-patterns: generic wrappers, unjustified assumptions)
- `timeoff.PublicHolidayService#validateFileEnvelope` (the Tika precedent this generalizes)
- `people.EmployeeService#requireOwnedBy` (the anti-enumeration `NotFoundException` convention this mirrors)
- `docs/CURRENT_PHASE.md`'s Phase 1.7 brief (explicitly flags the outbox and Testcontainers-S3 questions as open, not to be assumed)
