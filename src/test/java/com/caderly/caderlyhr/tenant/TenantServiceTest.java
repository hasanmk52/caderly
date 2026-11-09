package com.caderly.caderlyhr.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link TenantService}'s Super Admin surface. Every method here runs from a thread
 * with no {@code TenantContext} set — exactly the Super Admin realm's situation (Global
 * Constraint 1) — so the load-bearing assertion throughout is simply "this does not throw
 * IllegalStateException", not just the more obvious value assertions.
 */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
class TenantServiceTest {

    @Autowired private TenantFacade tenantFacade;
    @Autowired private TenantRepository tenantRepository;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void createTenant_fromNoTenantContext_doesNotThrowAndPersistsGivenFields() {
        String slug = uniqueSlug();

        // The single most important assertion in this test: TenantContext is empty on this thread
        // (no @BeforeEach sets it), matching the Super Admin realm exactly. If createTenant's
        // runAsSystem wrapping were wrong, this line throws IllegalStateException.
        UUID tenantId =
                tenantFacade.createTenant(slug, "Acme Inc", "Asia/Kolkata", 32, "https://logo.example/a.png");

        assertThat(tenantId).isNotNull();
        Tenant saved = findTenant(tenantId);
        assertThat(saved.getSlug()).isEqualTo(slug);
        assertThat(saved.getName()).isEqualTo("Acme Inc");
        assertThat(saved.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(saved.getWeekendDays()).isEqualTo(32);
        assertThat(saved.getLogoUrl()).isEqualTo("https://logo.example/a.png");
        assertThat(saved.isSuspended()).isFalse();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void createTenant_freshlyCreatedTenant_resolvesImmediatelyViaBySlug() {
        String slug = uniqueSlug();

        tenantFacade.createTenant(slug, "Acme Inc", "UTC", 96, null);

        // Proves evictCache() is actually called: bySlug's own cache would otherwise have cached
        // the miss from any earlier lookup of this (fresh, unique) slug — though here it proves
        // more simply that a brand new tenant is visible right away, not after up to 60s.
        assertThat(tenantFacade.bySlug(slug)).isPresent();
    }

    @Test
    void createTenant_duplicateSlug_throwsConflict() {
        String slug = uniqueSlug();
        tenantFacade.createTenant(slug, "First", "UTC", 96, null);

        assertThatThrownBy(() -> tenantFacade.createTenant(slug, "Second", "UTC", 96, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createTenant_slugOfASoftDeletedTenant_stillConflicts() {
        String slug = uniqueSlug();
        UUID tenantId = tenantFacade.createTenant(slug, "First", "UTC", 96, null);
        tenantFacade.softDelete(tenantId);

        // tenant.slug has a bare UNIQUE constraint (V202607241000), not a partial index excluding
        // soft-deleted rows, so slug reuse is genuinely blocked even once the original tenant is
        // gone.
        assertThatThrownBy(() -> tenantFacade.createTenant(slug, "Second", "UTC", 96, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void suspend_thenReinstate_toggleTheColumnAndEvictTheCache() {
        String slug = uniqueSlug();
        UUID tenantId = tenantFacade.createTenant(slug, "Acme Inc", "UTC", 96, null);
        assertThat(tenantFacade.bySlug(slug)).hasValueSatisfying(t -> assertThat(t.suspended()).isFalse());

        tenantFacade.suspend(tenantId);

        assertThat(findTenant(tenantId).isSuspended()).isTrue();
        // bySlug immediately reflects the change rather than a stale cached value — proves eviction.
        assertThat(tenantFacade.bySlug(slug)).hasValueSatisfying(t -> assertThat(t.suspended()).isTrue());

        tenantFacade.reinstate(tenantId);

        assertThat(findTenant(tenantId).isSuspended()).isFalse();
        assertThat(tenantFacade.bySlug(slug)).hasValueSatisfying(t -> assertThat(t.suspended()).isFalse());
    }

    @Test
    void suspend_unknownTenantId_throwsNotFound() {
        Throwable thrown = catchThrowable(() -> tenantFacade.suspend(UUID.randomUUID()));
        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reinstate_unknownTenantId_throwsNotFound() {
        assertThatThrownBy(() -> tenantFacade.reinstate(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void softDelete_setsDeletedAtAndEvictsCacheSoBySlugNoLongerResolvesIt() {
        String slug = uniqueSlug();
        UUID tenantId = tenantFacade.createTenant(slug, "Acme Inc", "UTC", 96, null);
        assertThat(tenantFacade.bySlug(slug)).isPresent();

        tenantFacade.softDelete(tenantId);

        assertThat(findTenant(tenantId).getDeletedAt()).isNotNull();
        // bySlug filters on deletedAt IS NULL — the cache must not keep serving the pre-delete hit.
        assertThat(tenantFacade.bySlug(slug)).isEmpty();
    }

    @Test
    void softDelete_unknownTenantId_throwsNotFound() {
        assertThatThrownBy(() -> tenantFacade.softDelete(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listForAdmin_newestFirstOnAPageOf25_includingSuspendedAndSoftDeleted() {
        UUID activeId = tenantFacade.createTenant(uniqueSlug(), "Active Co", "UTC", 96, null);
        UUID suspendedId = tenantFacade.createTenant(uniqueSlug(), "Suspended Co", "UTC", 96, null);
        tenantFacade.suspend(suspendedId);
        UUID deletedId = tenantFacade.createTenant(uniqueSlug(), "Deleted Co", "UTC", 96, null);
        tenantFacade.softDelete(deletedId);

        var page = tenantFacade.listForAdmin(0);
        List<TenantFacade.TenantAdminView> all = page.getContent();

        assertThat(page.getSize()).isEqualTo(TenantFacade.ADMIN_PAGE_SIZE);
        assertThat(all).extracting(TenantFacade.TenantAdminView::id)
                .startsWith(deletedId, suspendedId, activeId);
        assertThat(all).filteredOn(v -> v.id().equals(suspendedId))
                .singleElement()
                .satisfies(v -> assertThat(v.suspended()).isTrue());
        assertThat(all).filteredOn(v -> v.id().equals(deletedId))
                .singleElement()
                .satisfies(v -> assertThat(v.deletedAt()).isNotNull());
    }

    private static String uniqueSlug() {
        return "svc-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Direct assertions against the repository, bypassing the facade, still open a Hibernate
     * session — which resolves a multi-tenant identifier for ANY entity, {@code tenant} included
     * (Global Constraint 1) — so this needs the same {@code runAsSystem} wrapping production code
     * requires, exactly like {@link TenantIsolationTestBase#asTenant}.
     */
    private Tenant findTenant(UUID tenantId) {
        return TenantContext.runAsSystem(
                "test: read tenant row directly", () -> tenantRepository.findById(tenantId).orElseThrow());
    }

    @Test
    void currentBranding_withAnUploadedLogo_returnsItsAbsoluteTenantSubdomainUrl() throws Exception {
        String slug = uniqueSlug();
        UUID tenantId = tenantFacade.createTenant(slug, "Acme Inc", "UTC", 96, "https://legacy.example/a.png");
        tenantFacade.changeLogo(tenantId, "logo.png", pngBytes());
        TenantContext.set(tenantId);

        String logoUrl = tenantFacade.currentBranding().logoUrl();

        // Uploaded logo wins over the legacy free-text URL.
        assertThat(logoUrl).startsWith("https://" + slug + ".localhost/tenant-logo?v=");
    }

    @Test
    void currentBranding_withOnlyTheLegacyUrl_keepsReturningIt() {
        UUID tenantId = tenantFacade.createTenant(uniqueSlug(), "Acme Inc", "UTC", 96, "https://legacy.example/a.png");
        TenantContext.set(tenantId);

        assertThat(tenantFacade.currentBranding().logoUrl()).isEqualTo("https://legacy.example/a.png");
    }

    @Test
    void currentBranding_withNoLogoAtAll_returnsNull() {
        UUID tenantId = tenantFacade.createTenant(uniqueSlug(), "Acme Inc", "UTC", 96, null);
        TenantContext.set(tenantId);

        assertThat(tenantFacade.currentBranding().logoUrl()).isNull();
    }

    private static byte[] pngBytes() throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
