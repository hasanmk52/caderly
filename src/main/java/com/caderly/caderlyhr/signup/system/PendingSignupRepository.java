package com.caderly.caderlyhr.signup.system;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * System-scoped, so every call must run under {@code TenantContext.runAsSystem}: there is no
 * tenant to resolve (ADR 0022 decision C). {@code signup.SignupService} is the only caller.
 */
public interface PendingSignupRepository extends JpaRepository<PendingSignup, UUID> {

    Optional<PendingSignup> findByTokenHash(String tokenHash);

    boolean existsBySlug(String slug);

    /** A live hold: the address is free again once its link has expired, purged or not. */
    boolean existsBySlugAndExpiresAtAfter(String slug, Instant now);

    /**
     * Holds whose link is still live. A link lives exactly as long as the daily window, so this is
     * also "sign-ups started in the window and not yet confirmed".
     */
    long countByExpiresAtAfter(Instant now);

    /** Releases every hold whose link has expired, including any on the address being requested. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingSignup p where p.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    /** One live sign-up per email: a new request replaces the previous one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingSignup p where p.adminEmail = :email")
    int deleteByAdminEmail(@Param("email") String email);

    /**
     * Claims a sign-up for provisioning. Returns 0 when a concurrent request claimed it first,
     * which is what makes a confirmation link single-use under a double click.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingSignup p where p.id = :id")
    int claim(@Param("id") UUID id);
}
