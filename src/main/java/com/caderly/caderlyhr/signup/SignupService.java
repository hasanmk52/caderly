package com.caderly.caderlyhr.signup;

import com.caderly.caderlyhr.common.ConflictException;
import com.caderly.caderlyhr.common.SecureToken;
import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.notifications.EmailEvent;
import com.caderly.caderlyhr.notifications.EmailOutboxService;
import com.caderly.caderlyhr.signup.system.PendingSignup;
import com.caderly.caderlyhr.signup.system.PendingSignupRepository;
import com.caderly.caderlyhr.superadmin.TenantProvisioningService;
import com.caderly.caderlyhr.superadmin.TenantProvisioningService.SelfServeWorkspace;
import com.caderly.caderlyhr.tenant.SlugPolicy;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-serve sign-up (ADR 0022 decision C): {@link #start} holds an address and emails a link;
 * {@link #verify} turns a confirmed link into a workspace.
 *
 * <p>Every database touch runs under {@code TenantContext.runAsSystem} because there is no tenant
 * yet, and the transaction is opened <em>inside</em> it with a {@link TransactionTemplate} rather
 * than {@code @Transactional}: {@code TenantSessionVariableListener} reads the context when a
 * transaction begins, so a transaction started before system mode would fail closed — the same
 * ordering constraint {@code TenantService} documents.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    /** How long a confirmation link works, and so how long an address is held. */
    static final Duration LINK_TTL = Duration.ofHours(24);

    private final PendingSignupRepository pendings;
    private final TenantFacade tenants;
    private final TenantProvisioningService provisioning;
    private final EmailOutboxService emailOutbox;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnlyTransactions;
    private final Clock clock;
    private final boolean enabled;
    private final int seatLimit;
    private final int dailyLimit;
    private final String baseDomain;

    SignupService(
            PendingSignupRepository pendings,
            TenantFacade tenants,
            TenantProvisioningService provisioning,
            EmailOutboxService emailOutbox,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${caderly.signup.enabled:false}") boolean enabled,
            @Value("${caderly.signup.seat-limit:50}") int seatLimit,
            @Value("${caderly.signup.daily-limit:20}") int dailyLimit,
            @Value("${caderly.base-domain:localhost}") String baseDomain) {
        this.pendings = pendings;
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.emailOutbox = emailOutbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions.setReadOnly(true);
        this.clock = clock;
        this.enabled = enabled;
        this.seatLimit = seatLimit;
        this.dailyLimit = dailyLimit;
        this.baseDomain = baseDomain;
    }

    /** The kill switch (ADR 0022 decision E). Off until the legal pages have been reviewed. */
    public boolean isOpen() {
        return enabled;
    }

    /** {@code acme} → {@code acme.caderly.app}: how the address is shown to people. */
    public String workspaceHost(String slug) {
        return SlugPolicy.normalise(slug) + "." + baseDomain;
    }

    /**
     * Whether an address could be requested right now. Advisory only — {@link #start} checks
     * again inside its transaction, and the unique index on {@code pending_signup.slug} is the
     * last word.
     */
    public AddressAvailability checkAddress(String rawSlug) {
        String slug = SlugPolicy.normalise(rawSlug);
        Optional<SlugPolicy.Problem> problem = SlugPolicy.checkSelfServe(slug);
        if (problem.isPresent()) {
            return AddressAvailability.of(problem.get());
        }
        Instant now = clock.instant();
        boolean taken =
                TenantContext.runAsSystem(
                        "signup: check address " + slug,
                        () ->
                                Boolean.TRUE.equals(
                                        transactions.execute(
                                                status ->
                                                        tenants.isSlugTaken(slug)
                                                                || pendings.existsBySlugAndExpiresAtAfter(slug, now))));
        return taken ? AddressAvailability.TAKEN : AddressAvailability.AVAILABLE;
    }

    /**
     * Holds the address and queues the confirmation email, in one transaction (CLAUDE.md §6a).
     * Nothing outside {@code pending_signup} and {@code email_outbox} is written.
     *
     * @param siteBaseUrl the public site's origin, e.g. {@code https://caderly.app}; the link
     *     must come back there, not to a tenant host that doesn't exist yet
     * @throws ValidationException {@code SIGNUP_CLOSED}, {@code SIGNUP_DAILY_LIMIT} or an {@code
     *     SIGNUP_ADDRESS_*} code
     * @throws ConflictException {@code SIGNUP_ADDRESS_TAKEN}
     */
    public void start(SignupRequest request, String siteBaseUrl) {
        if (!enabled) {
            throw new ValidationException("SIGNUP_CLOSED", "New workspaces are paused");
        }
        String slug = SlugPolicy.normalise(request.slug());
        SlugPolicy.checkSelfServe(slug)
                .ifPresent(
                        problem -> {
                            throw new ValidationException(
                                    AddressAvailability.of(problem).errorCode(), "That address can't be used");
                        });
        String email = request.adminEmail().trim().toLowerCase(Locale.ROOT);
        String rawToken = SecureToken.generate();
        Instant now = clock.instant();

        try {
            TenantContext.runAsSystem(
                    "signup: hold address " + slug,
                    () ->
                            transactions.execute(
                                    status -> {
                                        pendings.deleteExpired(now);
                                        pendings.deleteByAdminEmail(email);
                                        if (tenants.isSlugTaken(slug) || pendings.existsBySlug(slug)) {
                                            throw addressTaken();
                                        }
                                        // After deleteByAdminEmail, so a repeat request from the same
                                        // email replaces its hold instead of counting twice. Throwing
                                        // rolls that delete back, so the earlier link keeps working.
                                        if (signupsInWindow(now) >= dailyLimit) {
                                            throw new ValidationException(
                                                    "SIGNUP_DAILY_LIMIT", "Sign-ups are at today's limit");
                                        }
                                        pendings.saveAndFlush(
                                                new PendingSignup(
                                                        slug,
                                                        request.companyName().trim(),
                                                        request.timezone(),
                                                        request.weekendDays(),
                                                        request.adminName().trim(),
                                                        email,
                                                        SecureToken.hash(rawToken),
                                                        now.plus(LINK_TTL)));
                                        emailOutbox.enqueue(
                                                EmailEvent.SIGNUP_VERIFY,
                                                email,
                                                Map.of(
                                                        "verifyUrl", verifyUrl(siteBaseUrl, rawToken),
                                                        "companyName", request.companyName().trim(),
                                                        "adminName", request.adminName().trim(),
                                                        "workspaceHost", workspaceHost(slug)),
                                                workspaceHost(slug));
                                        return null;
                                    }));
        } catch (DataIntegrityViolationException raceLost) {
            // Two sign-ups for the same address in the same instant: the unique index let one in.
            throw addressTaken();
        }
        // Never the token, never the link: both are live credentials until used.
        log.info("Sign-up started for {}", slug);
    }

    /**
     * Sign-ups in the last {@link #LINK_TTL}: unconfirmed holds still live, plus workspaces made
     * in the window. A confirmation moves a sign-up from the first count to the second (the hold
     * is deleted, the tenant is created), so nothing is counted twice or dropped.
     */
    private long signupsInWindow(Instant now) {
        return pendings.countByExpiresAtAfter(now) + tenants.countSelfServeCreatedSince(now.minus(LINK_TTL));
    }

    /**
     * Looks a confirmation link up without spending it. The link in the email lands on a page with
     * a button rather than provisioning on GET, because mail scanners (Microsoft Safe Links and the
     * like) open links before the person does and would otherwise use up the single-use token.
     */
    public Optional<PendingConfirmation> preview(String rawToken) {
        String tokenHash = SecureToken.hash(rawToken);
        Instant now = clock.instant();
        Optional<PendingSignup> found =
                TenantContext.runAsSystem(
                        "signup: look up confirmation link",
                        () -> readOnlyTransactions.execute(status -> pendings.findByTokenHash(tokenHash)));
        return found == null
                ? Optional.empty()
                : found.filter(signup -> !signup.isExpired(now))
                        .map(signup -> new PendingConfirmation(signup.companyName(), workspaceHost(signup.slug())));
    }

    /**
     * Claims a confirmation link and provisions its workspace. The pending row is deleted first,
     * in its own transaction, so the link is spent even if provisioning then fails.
     *
     * @param tenantBaseUrl the new tenant's origin for a slug, e.g. {@code https://acme.caderly.app}
     */
    public VerifyResult verify(String rawToken, Function<String, String> tenantBaseUrl) {
        String tokenHash = SecureToken.hash(rawToken);
        Instant now = clock.instant();
        Optional<PendingSignup> claimed =
                TenantContext.runAsSystem(
                        "signup: claim confirmation link",
                        () ->
                                transactions.execute(
                                        status -> {
                                            Optional<PendingSignup> found = pendings.findByTokenHash(tokenHash);
                                            if (found.isEmpty() || pendings.claim(found.get().requireId()) == 0) {
                                                return Optional.<PendingSignup>empty();
                                            }
                                            return found.filter(signup -> !signup.isExpired(now));
                                        }));
        if (claimed == null || claimed.isEmpty()) {
            // Unknown, already used and expired look the same on purpose: telling them apart
            // would confirm a link once existed (same rule as invite tokens, PRD FR-1.4).
            return new VerifyResult.LinkInvalid();
        }
        PendingSignup signup = claimed.get();
        try {
            SelfServeWorkspace workspace =
                    provisioning.provisionSelfServe(
                            signup.slug(),
                            signup.companyName(),
                            signup.timezone(),
                            signup.weekendDays(),
                            seatLimit,
                            signup.adminName(),
                            signup.adminEmail(),
                            tenantBaseUrl.apply(signup.slug()),
                            signup.requireId());
            log.info("Self-serve workspace {} created from sign-up {}", signup.slug(), signup.requireId());
            return new VerifyResult.Created(signup.slug(), workspace.acceptUrl());
        } catch (ConflictException taken) {
            log.warn("Confirmed sign-up for {} lost the address before provisioning", signup.slug());
            return new VerifyResult.AddressTaken(signup.slug());
        }
    }

    /** Deletes expired sign-ups, releasing their addresses. Returns how many went. */
    public int purgeExpired() {
        Instant now = clock.instant();
        Integer removed =
                TenantContext.runAsSystem(
                        "signup: purge expired", () -> transactions.execute(status -> pendings.deleteExpired(now)));
        return removed == null ? 0 : removed;
    }

    private static ConflictException addressTaken() {
        return new ConflictException("SIGNUP_ADDRESS_TAKEN", "That address is already taken");
    }

    private static String verifyUrl(String siteBaseUrl, String rawToken) {
        return siteBaseUrl + "/signup/verify?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
