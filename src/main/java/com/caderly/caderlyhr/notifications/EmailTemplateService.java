package com.caderly.caderlyhr.notifications;

import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.TenantBranding;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.ISpringTemplateEngine;

/**
 * Renders one email body from {@code templates/email/} (PRD §17.1, FR-9.1).
 *
 * <p>Rendering happens at <em>enqueue</em> time and the finished HTML is stored on the outbox row,
 * so {@code EmailDispatcher} and {@code EmailDelivery} are untouched by this phase: the dispatcher
 * still sends {@code body_html} verbatim. That also means a template edit never retroactively
 * rewrites mail that was already decided on — the row records what we said we would send.
 *
 * <p>Reuses the application's own {@code SpringTemplateEngine} rather than standing up a second
 * one. Its resolver prefix is already {@code classpath:/templates/}, and Boot has already wired the
 * app {@code MessageSource} into it, so {@code #{...}} resolves in an email exactly as it does in a
 * page (ADR 0013).
 *
 * <p><strong>Email templates must not use {@code @{...}} link expressions.</strong> A plain {@link
 * Context} is not an {@code IWebContext} — there is no request to build a URL against, and by
 * design: mail is enqueued on a request thread today but on a scheduler thread for the reminder
 * events. Absolute URLs come in as model attributes instead.
 */
@Service
public class EmailTemplateService {

    /**
     * Mail is composed at enqueue time, which is architecturally background work even when it
     * happens to run inside a request (ADR 0013 Decision D). MVP ships English only.
     */
    static final Locale EMAIL_LOCALE = Locale.ENGLISH;

    private final ISpringTemplateEngine templateEngine;
    private final TenantFacade tenants;

    EmailTemplateService(ISpringTemplateEngine templateEngine, TenantFacade tenants) {
        this.templateEngine = templateEngine;
        this.tenants = tenants;
    }

    String render(EmailEvent event, Map<String, Object> model) {
        Context context = new Context(EMAIL_LOCALE, model);
        // No tenant in context: leave both unset so the chrome shows the Caderly wordmark alone.
        if (TenantContext.get().isPresent()) {
            TenantBranding branding = tenants.currentBranding();
            context.setVariable("tenantName", branding.name());
            context.setVariable("logoUrl", branding.logoUrl());
        }
        return templateEngine.process(event.templatePath(), context);
    }
}
