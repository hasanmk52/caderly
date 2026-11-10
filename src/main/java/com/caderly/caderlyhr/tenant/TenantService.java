package com.caderly.caderlyhr.tenant;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.storage.FileStorage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lookup behind a Caffeine cache: the resolution filter hits this on every request, and the
 * tenant row changes rarely. Misses are cached too (as empty Optionals) so a flood of requests for
 * unknown subdomains cannot hammer the database. Consequence: slug changes, suspensions, and new
 * tenants take up to CACHE_TTL to become visible — acceptable at this scale (ADR 0003).
 *
 * <p>The lookup runs via {@code TenantContext.runAsSystem} (CLAUDE.md §5 rule 6, audited): {@code
 * tenant} has no {@code tenant_id} and this call is what establishes tenant context in the first
 * place, so it must run before any tenant is known (see ADR 0004). Deliberately not
 * {@code @Transactional} at this method level — that would start the transaction (and resolve a
 * Hibernate tenant identifier) before {@code runAsSystem} sets system mode. {@link
 * TenantRepository}'s own generated implementation already opens its own short-lived transaction
 * per call.
 */
@Service
public class TenantService implements TenantFacade {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long CACHE_MAX_SIZE = 1_000;

    private final TenantRepository repository;
    private final Clock clock;
    private final FileStorage storage;
    private final TenantLogoValidator logoValidator;
    private final String baseDomain;
    private final String publicScheme;
    private final String publicPort;

    private final Cache<String, Optional<TenantSummary>> bySlug =
            Caffeine.newBuilder().maximumSize(CACHE_MAX_SIZE).expireAfterWrite(CACHE_TTL).build();

    TenantService(
            TenantRepository repository,
            Clock clock,
            FileStorage storage,
            TenantLogoValidator logoValidator,
            @Value("${caderly.base-domain:localhost}") String baseDomain,
            @Value("${caderly.public-scheme:https}") String publicScheme,
            @Value("${caderly.public-port:}") String publicPort) {
        this.repository = repository;
        this.clock = clock;
        this.storage = storage;
        this.logoValidator = logoValidator;
        this.baseDomain = baseDomain;
        this.publicScheme = publicScheme;
        this.publicPort = publicPort;
    }

    @Override
    public Optional<TenantSummary> bySlug(String slug) {
        return bySlug.get(
                slug,
                s ->
                        TenantContext.runAsSystem(
                                "resolve tenant by slug for request routing",
                                () -> repository.findBySlugAndDeletedAtIsNull(s).map(TenantSummary::of)));
    }

    @Override
    public List<UUID> listActiveTenantIds() {
        return TenantContext.runAsSystem(
                "list active tenants for scheduled job fan-out",
                () ->
                        repository.findAllByDeletedAtIsNullAndSuspendedFalse().stream()
                                .map(tenant -> java.util.Objects.requireNonNull(tenant.getId()))
                                .toList());
    }

    @Override
    public int currentWeekendDays() {
        return requireCurrent().getWeekendDays();
    }

    @Override
    public TenantBranding currentBranding() {
        Tenant tenant = requireCurrent();
        return new TenantBranding(tenant.getName(), emailLogoUrl(tenant));
    }

    /**
     * Mail clients fetch the logo from outside the app, so the address must be absolute and
     * qualified by the tenant's own subdomain ({@code /tenant-logo} resolves its tenant from the
     * host). Built from config rather than the request: mail is also enqueued on scheduler threads
     * with no request to read a host from. The legacy free-text URL is only a fallback.
     */
    private @Nullable String emailLogoUrl(Tenant tenant) {
        String version = tenant.getLogoVersion();
        if (version == null) {
            return tenant.getLogoUrl();
        }
        String port = publicPort.isBlank() ? "" : ":" + publicPort;
        return publicScheme + "://" + tenant.getSlug() + "." + baseDomain + port + "/tenant-logo?v=" + version;
    }

    /**
     * Super Admin write, so it runs under {@code runAsSystem} and, like {@link #createTenant}, is
     * not {@code @Transactional} at this level. The previous object is deleted only after the row
     * points at the new one, so a failure in between leaves a working logo, at worst an orphan file.
     */
    @Override
    public void changeLogo(UUID tenantId, String filename, byte[] content) {
        String contentType = logoValidator.validate(filename, content);
        String version = sha256Prefix(content);
        String newKey = tenantId + "/logo-" + UUID.randomUUID();
        storage.store(newKey, new ByteArrayInputStream(content), content.length, contentType);
        String previousKey =
                TenantContext.runAsSystem(
                        "superadmin: change logo of tenant " + tenantId,
                        () -> {
                            Tenant tenant = findByIdOrThrow(tenantId);
                            String previous = tenant.getLogoStorageKey();
                            tenant.changeLogo(newKey, contentType, version);
                            repository.save(tenant);
                            return previous;
                        });
        evictCache();
        if (previousKey != null) {
            storage.delete(previousKey);
        }
    }

    @Override
    public void clearLogo(UUID tenantId) {
        String previousKey =
                TenantContext.runAsSystem(
                        "superadmin: clear logo of tenant " + tenantId,
                        () -> {
                            Tenant tenant = findByIdOrThrow(tenantId);
                            String previous = tenant.getLogoStorageKey();
                            tenant.clearLogo();
                            repository.save(tenant);
                            return previous;
                        });
        evictCache();
        if (previousKey != null) {
            storage.delete(previousKey);
        }
    }

    @Override
    public Optional<TenantLogo> currentLogo() {
        Tenant tenant = requireCurrent();
        String key = tenant.getLogoStorageKey();
        String contentType = tenant.getLogoContentType();
        String version = tenant.getLogoVersion();
        if (key == null || contentType == null || version == null) {
            return Optional.empty();
        }
        try (InputStream in = storage.open(key)) {
            return Optional.of(new TenantLogo(contentType, in.readAllBytes(), version));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read logo for tenant " + tenant.getId(), e);
        }
    }

    private static String sha256Prefix(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JRE", e);
        }
    }

    @Override
    public ZoneId currentTimezone() {
        return ZoneId.of(requireCurrent().getTimezone());
    }

    @Override
    public NotificationSettings currentNotificationSettings() {
        Tenant tenant = requireCurrent();
        return new NotificationSettings(
                tenant.isNotifyHolidayReminder(),
                tenant.isNotifyDocumentExpiry(),
                tenant.isNotifyBirthday(),
                tenant.isNotifyWorkAnniversary());
    }

    /**
     * Not cached, unlike {@link #bySlug}: {@code TenantSummary} is what the 60s cache holds and it
     * carries none of these flags, so an Admin toggling a category sees the effect on the next
     * enqueue rather than up to a minute later.
     */
    @Override
    @Transactional
    public void updateNotificationSettings(NotificationSettings settings) {
        Tenant tenant = requireCurrent();
        tenant.updateNotificationSettings(
                settings.holidayReminder(),
                settings.documentExpiry(),
                settings.birthday(),
                settings.workAnniversary());
        repository.save(tenant);
    }

    /**
     * Super Admin tenant provisioning (PRD FR-1.8). Runs entirely under {@code runAsSystem}, same
     * as {@link #bySlug} — there is no tenant to be "in" yet — and deliberately not {@code
     * @Transactional} at this method level for the same reason {@link #bySlug} isn't: {@link
     * TenantRepository}'s generated methods ({@code existsBySlug}, {@code save}) each open their
     * own short-lived transaction, and by the time either opens, {@code runAsSystem} has already
     * armed system mode — the exact ordering Global Constraint 1 requires. A shared
     * {@code @Transactional} spanning both calls would instead open one transaction before
     * {@code runAsSystem} runs, which is the trap this method must avoid.
     */
    @Override
    public UUID createTenant(String slug, String name, String timezone, int weekendDays, @Nullable String logoUrl) {
        return createTenant(slug, name, timezone, weekendDays, logoUrl, null);
    }

    @Override
    public UUID createTenant(
            String slug,
            String name,
            String timezone,
            int weekendDays,
            @Nullable String logoUrl,
            @Nullable Integer seatLimit) {
        UUID tenantId =
                TenantContext.runAsSystem(
                        "create tenant " + slug,
                        () -> {
                            if (repository.existsBySlug(slug)) {
                                throw new ConflictException(
                                        "TENANT_SLUG_TAKEN", "A tenant with that slug already exists");
                            }
                            Tenant tenant = new Tenant(slug, name, timezone, weekendDays, logoUrl);
                            tenant.changeSeatLimit(seatLimit);
                            return repository.save(tenant).getId();
                        });
        evictCache();
        return Objects.requireNonNull(tenantId);
    }

    @Override
    public boolean isSlugTaken(String slug) {
        return TenantContext.runAsSystem("check slug availability " + slug, () -> repository.existsBySlug(slug));
    }

    @Override
    public long countSelfServeCreatedSince(Instant since) {
        return TenantContext.runAsSystem(
                "count self-serve workspaces created since " + since,
                () -> repository.countBySeatLimitIsNotNullAndCreatedAtAfter(since));
    }

    @Override
    public Optional<Integer> currentSeatLimit() {
        return Optional.ofNullable(requireCurrent().getSeatLimit());
    }

    @Override
    public void suspend(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: suspend tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.suspend();
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public void reinstate(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: reinstate tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.reinstate();
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public void softDelete(UUID tenantId) {
        TenantContext.runAsSystem(
                "superadmin: soft-delete tenant " + tenantId,
                () -> {
                    Tenant tenant = findByIdOrThrow(tenantId);
                    tenant.softDelete(clock.instant());
                    repository.save(tenant);
                    return null;
                });
        evictCache();
    }

    @Override
    public org.springframework.data.domain.Page<TenantAdminView> listForAdmin(int page) {
        var request = org.springframework.data.domain.PageRequest.of(
                Math.max(page, 0),
                ADMIN_PAGE_SIZE,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("createdAt"),
                        org.springframework.data.domain.Sort.Order.desc("id")));
        return TenantContext.runAsSystem(
                "superadmin: list tenants",
                () -> repository.findAll(request).map(TenantService::toAdminView));
    }

    @Override
    public Optional<TenantAdminView> find(UUID tenantId) {
        return TenantContext.runAsSystem(
                "superadmin: look up tenant " + tenantId,
                () -> repository.findById(tenantId).map(TenantService::toAdminView));
    }

    private Tenant findByIdOrThrow(UUID tenantId) {
        return repository
                .findById(tenantId)
                .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found"));
    }

    private static TenantAdminView toAdminView(Tenant tenant) {
        return new TenantAdminView(
                Objects.requireNonNull(tenant.getId()),
                tenant.getSlug(),
                tenant.getName(),
                tenant.isSuspended(),
                tenant.getLogoVersion() != null,
                tenant.getDeletedAt(),
                Objects.requireNonNull(tenant.getCreatedAt()));
    }

    private Tenant requireCurrent() {
        UUID tenantId = TenantContext.require();
        return repository
                .findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("No tenant row for id " + tenantId));
    }

    // Package-private: only tests need to force freshness.
    void evictCache() {
        bySlug.invalidateAll();
    }
}
