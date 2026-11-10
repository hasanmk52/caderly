package com.caderly.caderlyhr.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.notifications.EmailOutboxService;
import com.caderly.caderlyhr.signup.system.PendingSignupRepository;
import com.caderly.caderlyhr.superadmin.TenantProvisioningService;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** The kill switch (ADR 0022 decision E): with sign-up off, nothing is stored or sent. */
class SignupServiceClosedTest {

    @Test
    void start_whenSignupIsClosed_throwsSignupClosedAndTouchesNothing() {
        PendingSignupRepository pendings = mock(PendingSignupRepository.class);
        EmailOutboxService outbox = mock(EmailOutboxService.class);
        SignupService closed =
                new SignupService(
                        pendings,
                        mock(TenantFacade.class),
                        mock(TenantProvisioningService.class),
                        outbox,
                        mock(PlatformTransactionManager.class),
                        Clock.systemUTC(),
                        false,
                        50,
                        20,
                        "caderly.app");

        assertThat(closed.isOpen()).isFalse();
        assertThatThrownBy(
                        () ->
                                closed.start(
                                        new SignupRequest("Acme", "acme", "UTC", 96, "Aisha", "aisha@acme.test"),
                                        "https://caderly.app"))
                .isInstanceOf(CaderlyException.class)
                .extracting(e -> ((CaderlyException) e).errorCode())
                .isEqualTo("SIGNUP_CLOSED");
        verifyNoInteractions(pendings, outbox);
    }
}
