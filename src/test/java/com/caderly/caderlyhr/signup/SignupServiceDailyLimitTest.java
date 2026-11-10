package com.caderly.caderlyhr.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.notifications.EmailOutboxService;
import com.caderly.caderlyhr.signup.system.PendingSignup;
import com.caderly.caderlyhr.signup.system.PendingSignupRepository;
import com.caderly.caderlyhr.superadmin.TenantProvisioningService;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The global daily cap on self-serve sign-ups: live holds plus workspaces created in the window. */
class SignupServiceDailyLimitTest {

    private static final int LIMIT = 20;
    private static final SignupRequest REQUEST =
            new SignupRequest("Acme", "acme", "UTC", 96, "Aisha", "aisha@acme.test");

    private PendingSignupRepository pendings;
    private TenantFacade tenants;
    private EmailOutboxService outbox;
    private SignupService signups;

    @BeforeEach
    void setUp() {
        pendings = mock(PendingSignupRepository.class);
        tenants = mock(TenantFacade.class);
        outbox = mock(EmailOutboxService.class);
        signups =
                new SignupService(
                        pendings,
                        tenants,
                        mock(TenantProvisioningService.class),
                        outbox,
                        mock(PlatformTransactionManager.class),
                        Clock.systemUTC(),
                        true,
                        50,
                        LIMIT,
                        "caderly.app");
    }

    @Test
    void start_whenLivePendingPlusNewWorkspacesReachTheLimit_throwsDailyLimitAndStoresNothing() {
        when(pendings.countByExpiresAtAfter(any(Instant.class))).thenReturn(12L);
        when(tenants.countSelfServeCreatedSince(any(Instant.class))).thenReturn(8L);

        assertThatThrownBy(() -> signups.start(REQUEST, "https://caderly.app"))
                .isInstanceOf(CaderlyException.class)
                .extracting(e -> ((CaderlyException) e).errorCode())
                .isEqualTo("SIGNUP_DAILY_LIMIT");

        verify(pendings, never()).saveAndFlush(any(PendingSignup.class));
        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void start_whenOneBelowTheLimit_stillAcceptsTheSignup() {
        when(pendings.countByExpiresAtAfter(any(Instant.class))).thenReturn(11L);
        when(tenants.countSelfServeCreatedSince(any(Instant.class))).thenReturn(8L);
        assertThat(11L + 8L).isEqualTo(LIMIT - 1);

        signups.start(REQUEST, "https://caderly.app");

        verify(pendings).saveAndFlush(any(PendingSignup.class));
    }
}
