package com.caderly.caderlyhr.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.TestcontainersConfiguration;
import com.caderly.caderlyhr.audit.AuditEntryRepository;
import com.caderly.caderlyhr.audit.system.AuditEntry;
import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.common.SecureToken;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.notifications.system.EmailOutboxRepository;
import com.caderly.caderlyhr.signup.system.PendingSignup;
import com.caderly.caderlyhr.signup.system.PendingSignupRepository;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Self-serve sign-up end to end below the web layer (ADR 0022 decision C). Every call runs from a
 * thread with no {@code TenantContext}, exactly like a base-domain request, so "does not throw
 * {@code IllegalStateException}" is part of every assertion here.
 */
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
@SpringBootTest
class SignupServiceTest {

    private static final String SITE = "https://localhost";
    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_%\\-]+)");
    private static final Function<String, String> TENANT_URL = slug -> "https://" + slug + ".localhost";

    @Autowired private SignupService signups;
    @Autowired private PendingSignupRepository pendings;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository users;
    @Autowired private EmployeeRepository employees;
    @Autowired private AuditEntryRepository auditEntries;
    @Autowired private MutableClock clock;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void start_holdsTheAddressStoresOnlyATokenHashAndQueuesOneVerificationEmail() {
        String slug = uniqueSlug();
        String email = uniqueEmail();

        signups.start(request(slug, email), SITE);

        PendingSignup pending = findPending(slug).orElseThrow();
        assertThat(pending.adminEmail()).isEqualTo(email);
        assertThat(pending.tokenHash()).hasSize(64);
        assertThat(pending.expiresAt()).isEqualTo(clock.instant().plus(SignupService.LINK_TTL));

        List<EmailOutbox> mail = mailTo(email);
        assertThat(mail).hasSize(1);
        assertThat(mail.getFirst().eventType()).isEqualTo("SIGNUP_VERIFY");
        assertThat(mail.getFirst().tenantId()).isNull();
        String rawToken = tokenIn(mail.getFirst());
        // The stored value is the hash of what was emailed, never the token itself.
        assertThat(pending.tokenHash()).isEqualTo(SecureToken.hash(rawToken)).isNotEqualTo(rawToken);
        assertThat(mail.getFirst().bodyHtml()).contains(SITE + "/signup/verify?token=");
    }

    @Test
    void start_createsNoTenantUntilTheLinkIsOpened() {
        String slug = uniqueSlug();

        signups.start(request(slug, uniqueEmail()), SITE);

        assertThat(tenantBySlug(slug)).isEmpty();
    }

    @Test
    void verify_provisionsTheWorkspaceWithTheSeatLimitAndReturnsTheAdminsAcceptLink() {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(slug, email), SITE);
        String rawToken = tokenIn(mailTo(email).getLast());

        VerifyResult result = signups.verify(rawToken, TENANT_URL);

        assertThat(result).isInstanceOf(VerifyResult.Created.class);
        VerifyResult.Created created = (VerifyResult.Created) result;
        assertThat(created.acceptUrl()).startsWith("https://" + slug + ".localhost/accept-invite?token=");

        Tenant tenant = tenantBySlug(slug).orElseThrow();
        assertThat(tenant.getName()).isEqualTo("Acme Trading LLC");
        assertThat(tenant.getTimezone()).isEqualTo("Asia/Dubai");
        assertThat(tenant.getWeekendDays()).isEqualTo(96);
        assertThat(tenant.getSeatLimit()).isEqualTo(50);

        TenantContext.set(tenant.getId());
        try {
            AppUser admin = users.findByEmail(email).orElseThrow();
            assertThat(admin.status()).isEqualTo(UserStatus.INVITED);
            assertThat(admin.roles()).containsExactly(Role.ADMIN);

            Employee founder = employees.findByUserId(admin.requireId()).orElseThrow();
            assertThat(founder.firstName()).isEqualTo("Aisha");
            assertThat(founder.lastName()).isEqualTo("Rahman");
            assertThat(founder.status()).isEqualTo(EmployeeStatus.INVITED);
        } finally {
            TenantContext.clear();
        }
        assertThat(findPending(slug)).isEmpty();
    }

    @Test
    void verify_recordsTheCreationInTheAuditLogAsSelfSignup() {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(slug, email), SITE);

        signups.verify(tokenIn(mailTo(email).getLast()), TENANT_URL);

        UUID tenantId = tenantBySlug(slug).orElseThrow().getId();
        List<AuditEntry> rows =
                TenantContext.runAsSystem(
                        "test: read audit",
                        () ->
                                auditEntries.findAll().stream()
                                        .filter(row -> tenantId.equals(row.tenantId()) && "Tenant".equals(row.entityType()))
                                        .toList());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().actorRole()).isEqualTo("SELF_SIGNUP");
    }

    @Test
    void verify_aSecondTime_isRejectedAndCreatesNothingMore() {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(slug, email), SITE);
        String rawToken = tokenIn(mailTo(email).getLast());
        signups.verify(rawToken, TENANT_URL);

        VerifyResult again = signups.verify(rawToken, TENANT_URL);

        assertThat(again).isInstanceOf(VerifyResult.LinkInvalid.class);
    }

    @Test
    void verify_afterTheLinkExpired_isRejectedAndReleasesTheAddress() {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(slug, email), SITE);
        String rawToken = tokenIn(mailTo(email).getLast());
        clock.advance(SignupService.LINK_TTL.plus(Duration.ofMinutes(1)));

        VerifyResult result = signups.verify(rawToken, TENANT_URL);

        assertThat(result).isInstanceOf(VerifyResult.LinkInvalid.class);
        assertThat(tenantBySlug(slug)).isEmpty();
        assertThat(signups.checkAddress(slug)).isEqualTo(AddressAvailability.AVAILABLE);
    }

    @Test
    void preview_showsTheSignupWithoutSpendingTheLink() {
        String slug = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(slug, email), SITE);
        String rawToken = tokenIn(mailTo(email).getLast());

        Optional<PendingConfirmation> first = signups.preview(rawToken);
        Optional<PendingConfirmation> second = signups.preview(rawToken);

        assertThat(first).contains(new PendingConfirmation("Acme Trading LLC", signups.workspaceHost(slug)));
        assertThat(second).isEqualTo(first);
        assertThat(findPending(slug)).isPresent();
        assertThat(signups.verify(rawToken, TENANT_URL)).isInstanceOf(VerifyResult.Created.class);
    }

    @Test
    void preview_afterExpiryOrUse_isEmpty() {
        String email = uniqueEmail();
        signups.start(request(uniqueSlug(), email), SITE);
        String used = tokenIn(mailTo(email).getLast());
        signups.verify(used, TENANT_URL);

        String otherEmail = uniqueEmail();
        signups.start(request(uniqueSlug(), otherEmail), SITE);
        String expired = tokenIn(mailTo(otherEmail).getLast());
        clock.advance(SignupService.LINK_TTL.plus(Duration.ofMinutes(1)));

        assertThat(signups.preview(used)).isEmpty();
        assertThat(signups.preview(expired)).isEmpty();
        assertThat(signups.preview(SecureToken.generate())).isEmpty();
    }

    @Test
    void verify_withAnUnknownToken_isRejected() {
        assertThat(signups.verify(SecureToken.generate(), TENANT_URL)).isInstanceOf(VerifyResult.LinkInvalid.class);
    }

    @Test
    void start_forAnAddressHeldByAnotherPendingSignup_isRejectedAsTaken() {
        String slug = uniqueSlug();
        signups.start(request(slug, uniqueEmail()), SITE);

        assertThatThrownBy(() -> signups.start(request(slug, uniqueEmail()), SITE))
                .isInstanceOf(CaderlyException.class)
                .extracting(e -> ((CaderlyException) e).errorCode())
                .isEqualTo("SIGNUP_ADDRESS_TAKEN");
        assertThat(signups.checkAddress(slug)).isEqualTo(AddressAvailability.TAKEN);
    }

    @Test
    void start_forAnAddressOwnedByAnExistingTenant_isRejectedAsTaken() {
        String slug = uniqueSlug();
        TenantContext.runAsSystem("test: seed tenant", () -> tenants.save(new Tenant(slug, "Existing Co")));

        assertThatThrownBy(() -> signups.start(request(slug, uniqueEmail()), SITE))
                .extracting(e -> ((CaderlyException) e).errorCode())
                .isEqualTo("SIGNUP_ADDRESS_TAKEN");
    }

    @Test
    void start_forAReservedAddress_isRejectedWithoutTouchingTheDatabase() {
        String email = uniqueEmail();

        assertThatThrownBy(() -> signups.start(request("admin", email), SITE))
                .extracting(e -> ((CaderlyException) e).errorCode())
                .isEqualTo("SIGNUP_ADDRESS_RESERVED");
        assertThat(mailTo(email)).isEmpty();
    }

    @Test
    void start_againWithTheSameEmail_replacesTheEarlierHold() {
        String first = uniqueSlug();
        String second = uniqueSlug();
        String email = uniqueEmail();
        signups.start(request(first, email), SITE);

        signups.start(request(second, email), SITE);

        assertThat(findPending(first)).isEmpty();
        assertThat(findPending(second)).isPresent();
        assertThat(signups.checkAddress(first)).isEqualTo(AddressAvailability.AVAILABLE);
    }

    @Test
    void purgeExpired_deletesOnlyExpiredSignups() {
        String stale = uniqueSlug();
        signups.start(request(stale, uniqueEmail()), SITE);
        clock.advance(SignupService.LINK_TTL.plus(Duration.ofMinutes(1)));
        String fresh = uniqueSlug();
        signups.start(request(fresh, uniqueEmail()), SITE);

        signups.purgeExpired();

        assertThat(findPending(stale)).isEmpty();
        assertThat(findPending(fresh)).isPresent();
    }

    @Test
    void checkAddress_explainsWhyAnAddressCantBeUsed() {
        assertThat(signups.checkAddress("")).isEqualTo(AddressAvailability.REQUIRED);
        assertThat(signups.checkAddress("ab")).isEqualTo(AddressAvailability.TOO_SHORT);
        assertThat(signups.checkAddress("-acme")).isEqualTo(AddressAvailability.INVALID_FORMAT);
        assertThat(signups.checkAddress("www")).isEqualTo(AddressAvailability.RESERVED);
        assertThat(signups.checkAddress(uniqueSlug())).isEqualTo(AddressAvailability.AVAILABLE);
    }

    private static SignupRequest request(String slug, String email) {
        return new SignupRequest("Acme Trading LLC", slug, "Asia/Dubai", 96, "Aisha Rahman", email);
    }

    private Optional<PendingSignup> findPending(String slug) {
        return TenantContext.runAsSystem(
                "test: read pending",
                () -> pendings.findAll().stream().filter(p -> p.slug().equals(slug)).findFirst());
    }

    private Optional<Tenant> tenantBySlug(String slug) {
        return TenantContext.runAsSystem(
                "test: read tenant", () -> tenants.findAll().stream().filter(t -> t.getSlug().equals(slug)).findFirst());
    }

    private List<EmailOutbox> mailTo(String email) {
        return TenantContext.runAsSystem(
                "test: read outbox",
                () -> outbox.findAll().stream().filter(row -> row.toEmail().equals(email)).toList());
    }

    private static String tokenIn(EmailOutbox mail) {
        Matcher matcher = TOKEN.matcher(mail.bodyHtml());
        assertThat(matcher.find()).as("token in email body").isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private static String uniqueSlug() {
        return "su-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueEmail() {
        return "founder-" + UUID.randomUUID() + "@example.test";
    }
}
