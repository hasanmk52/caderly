package com.caderly.caderlyhr.tenant;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Cross-module read access to tenants. This is the project's general rule for all modules: don't
 * reach into another module's internals, go through its facade
 */
public interface TenantFacade {

    Optional<TenantSummary> bySlug(String slug);

    /**
     * Every active (not deleted, not suspended) tenant id — the fan-out list for a cross-tenant
     * scheduled job (e.g. {@code people.EmployeeTerminationJob}). Callers must set {@code
     * TenantContext} to each id in turn before reading tenant-scoped data: unlike {@code
     * TenantContext.runAsSystem}, RLS is not bypassed for this data (ADR 0003) — real per-tenant
     * context is what makes each iteration's reads legitimate rather than a bypass attempt.
     */
    List<UUID> listActiveTenantIds();

    /**
     * The current tenant's weekend-day bitmask (PRD §12.5, §21) — decoded by {@code
     * timeoff.LeaveDurationCalculator.decodeWeekend}. Resolved from {@link TenantContext#require()};
     * {@code tenant} has no {@code tenant_id} of its own to filter by, so no {@code runAsSystem} is
     * needed the way {@link #bySlug} and {@link #listActiveTenantIds()} require.
     */
    int currentWeekendDays();

    /**
     * The current tenant's name and (optional) logo, for the transactional-email chrome (PRD
     * §17.1). There is no primary colour here on purpose: one Caderly brand serves every tenant
     * and a tenant logo is a guest on it, not a replacement for it (ADR 0016).
     */
    TenantBranding currentBranding();

    /**
     * The current tenant's configured zone. UI_Guidelines §11: stored UTC, displayed in the
     * tenant's timezone — a rule the JVM default zone cannot satisfy for a multi-tenant app.
     */
    ZoneId currentTimezone();

    /** PRD FR-9.3's category switches for the current tenant. */
    NotificationSettings currentNotificationSettings();

    /** Admin save from {@code /admin/notifications}. */
    void updateNotificationSettings(NotificationSettings settings);

    /**
     * Provisions a new tenant (PRD FR-1.8) for the Super Admin console. Runs entirely under
     * {@code TenantContext.runAsSystem} — there is no tenant to be "in" yet — and evicts the
     * {@link #bySlug} cache before returning, so a Super Admin who immediately visits the new
     * tenant's subdomain resolves it right away rather than waiting out the cache TTL.
     *
     * @throws com.caderly.caderlyhr.common.ConflictException if the slug is already taken — by any
     *     tenant, including a soft-deleted one, since {@code tenant.slug}'s {@code UNIQUE}
     *     constraint has no partial-index exception.
     */
    UUID createTenant(String slug, String name, String timezone, int weekendDays, @Nullable String logoUrl);

    /**
     * {@link #createTenant(String, String, String, int, String)} with a seat limit, for self-serve
     * sign-up (ADR 0022 decision F). {@code null} means no limit.
     */
    UUID createTenant(
            String slug,
            String name,
            String timezone,
            int weekendDays,
            @Nullable String logoUrl,
            @Nullable Integer seatLimit);

    /**
     * Whether any tenant — active, suspended or soft-deleted — already owns this slug. Uncached:
     * sign-up asks this right before holding an address and must not trust a 60-second-old answer.
     */
    boolean isSlugTaken(String slug);

    /**
     * How many self-serve workspaces (those carrying a seat limit) were created after {@code
     * since}, soft-deleted ones included. Backs the daily sign-up cap, which counts what was
     * created, not what is still active.
     */
    long countSelfServeCreatedSince(Instant since);

    /**
     * The current tenant's maximum number of non-terminated employees, or empty for no limit
     * (ADR 0022 decision F).
     */
    Optional<Integer> currentSeatLimit();

    /**
     * @throws com.caderly.caderlyhr.common.NotFoundException if {@code tenantId} doesn't exist.
     */
    void suspend(UUID tenantId);

    /**
     * @throws com.caderly.caderlyhr.common.NotFoundException if {@code tenantId} doesn't exist.
     */
    void reinstate(UUID tenantId);

    /**
     * Marks a tenant deleted (PRD FR-1.8). No restore method: soft-delete only by design.
     *
     * @throws com.caderly.caderlyhr.common.NotFoundException if {@code tenantId} doesn't exist.
     */
    void softDelete(UUID tenantId);

    /** Rows per page of the Super Admin console's tenant list. */
    int ADMIN_PAGE_SIZE = 25;

    /**
     * One page of every tenant, active, suspended, or soft-deleted alike, newest first — the one
     * place in the app that must see everything (Super Admin console's tenant list). Paged because
     * each row costs the console one employee count under that tenant's own context.
     */
    org.springframework.data.domain.Page<TenantAdminView> listForAdmin(int page);

    /**
     * A single tenant by id, active, suspended, or soft-deleted alike — the Super Admin console's
     * row lookup for the suspend/delete/impersonate actions, which land on one tenant rather than
     * a page of the list {@link #listForAdmin(int)} returns.
     */
    Optional<TenantAdminView> find(UUID tenantId);

    /**
     * Stores an uploaded logo for a tenant (Super Admin). Validates extension, size and magic
     * bytes (PNG/JPEG only), replaces any previous logo, and evicts the {@link #bySlug} cache so
     * the new logo shows immediately.
     *
     * @throws com.caderly.caderlyhr.common.ValidationException if the file is not an acceptable logo.
     * @throws com.caderly.caderlyhr.common.NotFoundException if {@code tenantId} doesn't exist.
     */
    void changeLogo(UUID tenantId, String filename, byte[] content);

    /**
     * Removes a tenant's uploaded logo, if any (idempotent).
     *
     * @throws com.caderly.caderlyhr.common.NotFoundException if {@code tenantId} doesn't exist.
     */
    void clearLogo(UUID tenantId);

    /** The current tenant's uploaded logo bytes, or empty if it has none. */
    Optional<TenantLogo> currentLogo();

    record TenantLogo(String contentType, byte[] content, String version) {}

    record TenantBranding(String name, @Nullable String logoUrl) {}

    record TenantAdminView(
            UUID id,
            String slug,
            String name,
            boolean suspended,
            boolean hasLogo,
            @Nullable Instant deletedAt,
            Instant createdAt) {}

    record NotificationSettings(
            boolean holidayReminder, boolean documentExpiry, boolean birthday, boolean workAnniversary) {}
}
