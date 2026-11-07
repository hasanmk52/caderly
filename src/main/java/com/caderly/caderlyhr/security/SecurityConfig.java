package com.caderly.caderlyhr.security;

import com.caderly.caderlyhr.identity.AppUserDetailsService;
import com.caderly.caderlyhr.tenant.TenantResolutionFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Authentication and authorization for the tenant realm (PRD §19.1, §19.2, §19.6; CLAUDE.md §6).
 * Shape agreed in ADR 0006 before implementation, per the CLAUDE.md §12 ask-first rule.
 *
 * <p>Still no <em>custom</em> {@code AuthenticationProvider} (ADR 0006 decision A): stock {@code
 * DaoAuthenticationProvider} over {@code AppUserDetailsService} is already tenant-scoped, because
 * {@code AppUser} carries {@code @TenantId}. The provider is <em>constructed here</em> rather
 * than inferred, because Spring Security only auto-wires one from a
 * {@code UserDetailsService} bean when the context holds exactly one, and {@code
 * superadmin.SuperAdminDetailsService} is a second — with two present it wires neither, for
 * either realm. Each chain therefore names its own store explicitly, which is also what keeps
 * Super Admin credentials from ever being checked against {@code app_user}, or the reverse.
 *
 * <p>{@code @Order(100)} leaves room below it for {@code superadmin.SuperAdminSecurityConfig}'s
 * {@code @Order(1)} chain and this class's own public-site chain at {@code @Order(50)}: the tenant
 * chain carries no {@code securityMatcher} and so matches everything, and an unordered set would
 * let it swallow {@code /superadmin/**} and the base domain.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** PRD §19.1's idle timeout is a container setting; this is the absolute cap on top of it. */
    private static final Duration MAX_SESSION_AGE = Duration.ofHours(24);

    /**
     * CLAUDE.md §6 A02. Cost 12 is a deliberate CPU cost; do not lower it here. Tests swap in a
     * cheaper encoder of their own, and {@code SecurityConfigPasswordEncoderTest} fails if this one
     * ever drops below 12.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * ADMIN implies MANAGER implies EMPLOYEE, so {@code @PreAuthorize} never has to enumerate
     * roles. Must be {@code static}: method-security infrastructure is built early in the context
     * lifecycle, and a non-static bean method here would drag this whole configuration class into
     * premature initialisation.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN")
                .implies("MANAGER")
                .role("MANAGER")
                .implies("EMPLOYEE")
                .build();
    }

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy hierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(hierarchy);
        return handler;
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * This realm's {@code SecurityContext} store, wired explicitly onto the chain below.
     *
     * <p>Composed exactly as {@code SecurityContextConfigurer} composes its own default (request
     * attribute first, {@code HttpSession} second, under the stock {@code SPRING_SECURITY_CONTEXT}
     * key), so naming it changes no behaviour. It is named for two reasons. First, symmetry: {@code
     * superadmin.SuperAdminSecurityConfig} states its repository explicitly because it must use a
     * <em>different</em> session key, and a reader comparing the two realms
     * should be able to see both answers rather than one answer and an omission. Second, {@code
     * web.ImpersonationController} establishes a session without going through an authentication
     * filter, and it has to save the context where <em>this</em> chain will look for it on the next
     * request — injecting the very same bean is what makes that true by construction instead of by
     * two independent constructions happening to agree.
     */
    @Bean
    SecurityContextRepository tenantSecurityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /** Without this, SessionRegistryImpl never learns that a session was destroyed. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Declared as its own bean, not inline in the registration below, so tests and operators can
     * reach {@code clearBuckets}. Boot does not double-register it: a filter already referenced
     * by a {@code FilterRegistrationBean} is excluded from automatic registration.
     */
    @Bean
    RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(
                SecurityPaths.LOGIN_PATH,
                SecurityPaths.FORGOT_PASSWORD_PATH,
                SecurityPaths.SUPER_ADMIN_LOGIN_PATH);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        // After tenant resolution (HIGHEST_PRECEDENCE) but before the security chain, so a flood
        // of login attempts is turned away without ever reaching the database.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    AbsoluteSessionTimeoutFilter absoluteSessionTimeoutFilter(Clock clock) {
        return new AbsoluteSessionTimeoutFilter(MAX_SESSION_AGE, clock);
    }

    @Bean
    FilterRegistrationBean<AbsoluteSessionTimeoutFilter> absoluteSessionTimeoutFilterRegistration(
            AbsoluteSessionTimeoutFilter filter) {
        FilterRegistrationBean<AbsoluteSessionTimeoutFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    ActorMdcFilter actorMdcFilter() {
        return new ActorMdcFilter();
    }

    /**
     * Order 0 (Boot's plain-servlet-filter default) is comfortably after Spring Security's own
     * chain, which Boot registers at order -100 — so {@code SecurityContextHolder} already holds
     * the authenticated principal, if any, by the time this filter runs (ADR 0017).
     */
    @Bean
    FilterRegistrationBean<ActorMdcFilter> actorMdcFilterRegistration(ActorMdcFilter filter) {
        return new FilterRegistrationBean<>(filter);
    }

    /**
     * The public site on the base domain (ADR 0022 decision B).
     *
     * <p>Matched by the marker {@link TenantResolutionFilter} puts on base-domain requests, never
     * by path: {@code /} and {@code /login} exist on both surfaces. Nobody authenticates here, so
     * there is no form login, logout or provider; the site's own routes are open and every other
     * path is denied and answered as 404, so a tenant URL cannot be reached through the base
     * domain. CSRF stays on — the sign-up form is the only state-changing request and it carries
     * the token like every other form in the app.
     */
    @Bean
    @Order(50)
    SecurityFilterChain publicSiteSecurityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher publicSite = TenantResolutionFilter::isPublicSite;
        http.securityMatcher(publicSite)
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/",
                                                SecurityPaths.LOGIN_PATH,
                                                "/login/go",
                                                SecurityPaths.SIGNUP_PATH,
                                                "/signup/sent",
                                                "/signup/created",
                                                SecurityPaths.SIGNUP_VERIFY_PATH,
                                                "/terms",
                                                "/privacy",
                                                "/dpa")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                SecurityPaths.SIGNUP_PATH,
                                                SecurityPaths.SIGNUP_VERIFY_PATH,
                                                SecurityPaths.SIGNUP_CHECK_ADDRESS_PATH)
                                        .permitAll()
                                        .requestMatchers(
                                                "/css/**",
                                                "/js/**",
                                                "/img/**",
                                                "/fonts/**",
                                                "/webjars/**",
                                                "/favicon.ico",
                                                "/favicon-16x16.png",
                                                "/favicon-32x32.png",
                                                "/apple-touch-icon.png",
                                                "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                // A denied path on the public site is a path that doesn't exist there. Both
                // handlers are needed: an anonymous visitor goes to the entry point, a request
                // that somehow carries a principal goes to the access-denied handler. A CSRF
                // failure is the exception: the path exists, the form is just stale, so it stays 403.
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, unauthenticated) ->
                                                        response.sendError(HttpServletResponse.SC_NOT_FOUND))
                                        .accessDeniedHandler(
                                                (request, response, denied) ->
                                                        response.sendError(
                                                                denied instanceof CsrfException
                                                                        ? HttpServletResponse.SC_FORBIDDEN
                                                                        : HttpServletResponse.SC_NOT_FOUND)))
                .headers(SecurityHeaders.standard());
        return http.build();
    }

    /**
     * Tenant-realm form login over {@code app_user}.
     *
     * <p>The provider is local to this chain — added through {@code http.authenticationProvider},
     * which feeds this {@code HttpSecurity}'s own {@code AuthenticationManagerBuilder} ({@code
     * HttpSecurity} is a prototype bean, so the builder is per-chain). Deliberately <em>not</em> a
     * shared {@code @Bean AuthenticationManager}: a single such bean becomes the parent manager of
     * every chain in the application, so a failed Super Admin login would fall through to it and
     * be retried against this realm's user store, and vice versa. Per-chain keeps the two realms
     * genuinely unable to see each other's credentials.
     *
     * <p>Going through the builder rather than {@code http.authenticationManager(...)} also keeps
     * the {@code AuthenticationEventPublisher} the builder already carries, which is what {@link
     * LoginAttemptListener} listens to for {@code login_audit} rows and the lockout counter.
     */
    @Bean
    @Order(100)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionRegistry sessionRegistry,
            AppUserDetailsService appUsers,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            LogoutHandler impersonationLogoutHandler,
            @Value("${management.server.port:-1}") int managementPort)
            throws Exception {
        DaoAuthenticationProvider tenantAuthentication = new DaoAuthenticationProvider(appUsers);
        tenantAuthentication.setPasswordEncoder(passwordEncoder);
        tenantAuthentication.afterPropertiesSet();

        http.authenticationProvider(tenantAuthentication)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                SecurityPaths.LOGIN_PATH,
                                                SecurityPaths.FORGOT_PASSWORD_PATH,
                                                "/reset-password",
                                                "/accept-invite",
                                                // Ticket-authenticated, like the two above: the browser
                                                // arriving here has no session yet — establishing one is
                                                // the endpoint's whole job (PRD FR-1.8). The ticket is
                                                // single-use and one minute old at most; see
                                                // identity.ImpersonationService.
                                                SecurityPaths.IMPERSONATE_PATH,
                                                // Token-authenticated, not session-authenticated (CLAUDE.md §6
                                                // A01's deliberate exception — see api.CalendarFeedController's
                                                // Javadoc). The URL-level permitAll() here is what makes the
                                                // controller's own @PreAuthorize("permitAll()") reachable at all.
                                                "/api/v1/calendar/ical.ics",
                                                "/webjars/**",
                                                "/css/**",
                                                "/js/**",
                                                "/img/**",
                                                "/fonts/**",
                                                // The login page shows the tenant's logo before any
                                                // session exists (ADR 0020); tenant-scoped by subdomain.
                                                "/tenant-logo",
                                                "/favicon.ico",
                                                "/favicon-16x16.png",
                                                "/favicon-32x32.png",
                                                "/apple-touch-icon.png",
                                                "/actuator/health",
                                                "/error")
                                        .permitAll()
                                        .requestMatchers(prometheusOnManagementPort(managementPort))
                                        .permitAll()
                                        // Default-deny (CLAUDE.md §6 A01): anything not listed above needs
                                        // authentication, and @PreAuthorize decides the role on top of it.
                                        .anyRequest()
                                        .authenticated())
                .formLogin(
                        form ->
                                form.loginPage(SecurityPaths.LOGIN_PATH)
                                        .loginProcessingUrl(SecurityPaths.LOGIN_PATH)
                                        .usernameParameter("email")
                                        .defaultSuccessUrl("/", true)
                                        // One generic failure destination for every cause, so the user
                                        // cannot learn whether the address exists, is locked, or is
                                        // disabled (CLAUDE.md §6 A07).
                                        .failureUrl(SecurityPaths.LOGIN_PATH + "?error")
                                        .permitAll())
                .logout(
                        logout ->
                                logout
                                        .logoutUrl("/logout")
                                        // Closes the audit trail for a support session ended via this ordinary
                                        // link rather than the dedicated "End impersonation" one — see
                                        // web.ImpersonationLogoutHandler's Javadoc for why this has to run
                                        // here (not just in ImpersonationController#end) and why it runs
                                        // before invalidateHttpSession below erases the session it reads.
                                        .addLogoutHandler(impersonationLogoutHandler)
                                        .logoutSuccessUrl(SecurityPaths.LOGIN_PATH + "?loggedOut")
                                        .invalidateHttpSession(true)
                                        .deleteCookies("JSESSIONID")
                                        .permitAll())
                .sessionManagement(
                        session ->
                                // A fresh session id on login defeats session fixation: a cookie value
                                // planted before authentication cannot survive into the session.
                                session
                                        .sessionFixation(fixation -> fixation.newSession())
                                        .maximumSessions(-1)
                                        .sessionRegistry(sessionRegistry))
                .headers(SecurityHeaders.standard());

        // CSRF stays at the Spring Security default (on, for every state-changing method). htmx
        // sends the token from the rendered form, so there is no reason to weaken this.
        return http.build();
    }

    /**
     * The Prometheus scrape path, open only when the request arrived on the management port. That
     * port is bound to loopback (docker-compose.yml) and never proxied, so a scraper on the host
     * can read it with no credentials while the same path on the application port, which Caddy
     * exposes, stays behind the default-deny rule. A port of zero or below (no separate management
     * port configured) matches nothing.
     */
    static RequestMatcher prometheusOnManagementPort(int managementPort) {
        return request ->
                managementPort > 0
                        && request.getLocalPort() == managementPort
                        && "/actuator/prometheus".equals(request.getRequestURI());
    }
}
