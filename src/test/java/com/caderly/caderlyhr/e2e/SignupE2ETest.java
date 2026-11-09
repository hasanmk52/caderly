package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.TenantContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Self-serve sign-up end to end (ADR 0022 decision C): a visitor on the base domain creates a
 * workspace, confirms by email, sets a password on the new subdomain and signs in as its Admin.
 * Also covers the progressive-enhancement script: two-step form, address suggested from the company
 * name, live availability check.
 */
class SignupE2ETest extends PlaywrightE2ETestBase {

    @Test
    void visitorCreatesAWorkspace_confirmsByEmail_andSignsInAsItsAdmin() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String slug = "falcon" + suffix + "-trading";
        String email = "founder-" + UUID.randomUUID() + "@example.test";
        String site = "http://localhost:" + port;

        page.navigate(site + "/signup");

        // Step 1: only the email is shown until Continue.
        assertThat(page.isVisible("#companyName")).isFalse();
        page.fill("#adminEmail", email);
        page.click("#signup-continue");

        // Step 2: the address is suggested from the company name and checked live.
        page.fill("#companyName", "Falcon" + suffix + " Trading LLC");
        assertThat(page.inputValue("#slug")).isEqualTo(slug);
        page.waitForSelector("#slug-status.ok");
        assertThat(page.textContent("#slug-status")).contains("is available");

        page.fill("#adminName", "Aisha Rahman");
        page.selectOption("#timezone", "Asia/Dubai");
        page.check("input[name=weekend][value=SAT_SUN]");
        page.check("input[name=acceptTerms]");
        page.click("button[type=submit]");

        page.waitForURL("**/signup/sent");
        assertThat(page.textContent("main")).contains(email);

        baseUrl = "http://" + slug + ".localhost:" + port;
        // Opening the link only shows a button (mail scanners open links too); pressing it creates
        // the workspace, and the ready page links on to its accept-invite page.
        page.navigate(site + "/signup/verify?token=" + tokenFromLastEmailTo(email));
        assertThat(page.textContent("main")).contains(slug + ".localhost");
        page.click("#confirm-signup");
        page.waitForURL("**/signup/created");
        page.click("#set-password");
        page.waitForURL(baseUrl + "/accept-invite**");

        page.fill("#password", EMPLOYEE_PASSWORD);
        page.fill("#confirmPassword", EMPLOYEE_PASSWORD);
        page.click("button[type=submit]");
        assertThat(page.url()).contains("/login").contains("inviteAccepted");

        loginAs(email, EMPLOYEE_PASSWORD);
        assertThat(page.url()).isEqualTo(baseUrl + "/");

        // The founder is an employee too, named from the sign-up form, not a bare login.
        page.navigate(baseUrl + "/profile");
        assertThat(page.textContent("main")).contains("Aisha Rahman").doesNotContain("no employee record");

        // Admin, not just a member: the user-management page opens.
        page.navigate(baseUrl + "/admin/users");
        assertThat(page.url()).isEqualTo(baseUrl + "/admin/users");

        // The link is single-use.
        page.navigate(site + "/signup/verify?token=" + tokenFromLastSignupEmailTo(email));
        assertThat(page.textContent("main")).contains("expired or was already used");
    }

    /**
     * After verifying, the outbox holds a second mail to the same address (the Admin invite), so
     * the confirmation link has to be picked out by its path rather than by "last email".
     */
    private String tokenFromLastSignupEmailTo(String email) {
        return TenantContext.runAsSystem(
                "e2e test: read outbox",
                () -> {
                    String body =
                            outbox.findAll().stream()
                                    .filter(row -> row.toEmail().equals(email))
                                    .map(EmailOutbox::bodyHtml)
                                    .filter(html -> html.contains("/signup/verify?token="))
                                    .reduce((first, second) -> second)
                                    .orElseThrow();
                    Matcher matcher = TOKEN_IN_LINK.matcher(body);
                    assertThat(matcher.find()).isTrue();
                    return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
                });
    }
}
