package com.caderly.caderlyhr.superadmin;

import com.caderly.caderlyhr.audit.EntityAuditListener;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.ImpersonationService;
import com.caderly.caderlyhr.identity.ImpersonationService.AdminAccount;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.security.SecurityPaths;
import com.caderly.caderlyhr.tenant.SlugPolicy;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantFacade;
import com.caderly.caderlyhr.tenant.TenantFacade.TenantAdminView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * The Super Admin console's tenant list (PRD FR-1.8): create, suspend/reinstate, soft-delete, and
 * impersonate — the operator's entire cross-tenant toolkit for this phase.
 *
 * <p>{@code @PreAuthorize} is repeated on every method as well as on the class (ArchitectureTest
 * requires it, same convention as {@code web.AdminAuditController}). No {@code @Transactional}
 * here (CLAUDE.md §7) — every write goes through {@link TenantFacade}, {@link
 * TenantProvisioningService}, or {@link ImpersonationService}, each of which owns its own
 * transaction boundary; see {@code superadmin}'s package Javadoc for why none of that can be a
 * shared, controller-level transaction (this realm's threads never carry a resolved {@code
 * TenantContext} the way a tenant request would).
 *
 * <p>Every list/count read here runs under either {@link TenantFacade#listForAdmin(int)}'s own
 * {@code runAsSystem} (the cross-tenant {@code tenant} table has no {@code tenant_id} to scope
 * by) or, for the per-tenant employee count, a real resolved {@link TenantContext} set one tenant
 * at a time — {@code employee} is RLS-protected, so {@code runAsSystem} alone would read zero rows
 * (ADR 0003). The loop below mirrors {@code people.EmployeeTerminationJob}'s serial fan-out
 * exactly: set, read, clear, one tenant at a time, never batched or parallelized (Global
 * Constraint 9 — this scales to a handful of pilot tenants, not thousands).
 */
@Controller
@PreAuthorize("hasRole('SUPER_ADMIN')")
class SuperAdminTenantController {

    private final TenantFacade tenants;
    private final TenantProvisioningService provisioning;
    private final PeopleFacade people;
    private final ImpersonationService impersonation;
    private final MessageSource messages;
    private final String baseDomain;
    private final EntityAuditListener auditListener;
    private final ObjectMapper mapper;

    SuperAdminTenantController(
            TenantFacade tenants,
            TenantProvisioningService provisioning,
            PeopleFacade people,
            ImpersonationService impersonation,
            MessageSource messages,
            @Value("${caderly.base-domain:localhost}") String baseDomain,
            EntityAuditListener auditListener,
            ObjectMapper mapper) {
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.people = people;
        this.impersonation = impersonation;
        this.messages = messages;
        this.baseDomain = baseDomain;
        this.auditListener = auditListener;
        this.mapper = mapper;
    }

    @GetMapping("/superadmin/tenants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("tenantForm", blankForm());
        populateTable(model, page);
        return "superadmin/tenants";
    }

    /**
     * Creates a tenant and invites its first Admin. A duplicate slug or another validation failure
     * re-renders the same page with the error attached to the {@code slug} field, following {@code
     * web.AdminOrganizationController}'s create-endpoint pattern for a {@link CaderlyException}
     * (this console has no {@code web.WebMessages} to reuse — it is package-private in a different
     * package — so the same "{@code error.} + errorCode" key scheme is re-applied inline via
     * {@link #errorDetail}).
     */
    @PostMapping("/superadmin/tenants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String create(@Valid @ModelAttribute("tenantForm") TenantCreateForm form, BindingResult binding, Model model) {
        // The reserved list is shared with self-serve sign-up (ADR 0022 decision D), so an address
        // the public form refuses cannot be handed out from here by accident either.
        if (!binding.hasFieldErrors("slug") && SlugPolicy.isReserved(form.slug())) {
            binding.rejectValue(
                    "slug",
                    "TENANT_SLUG_RESERVED",
                    messages.getMessage(
                            "validation.tenant-create-form.slug.reserved", null, LocaleContextHolder.getLocale()));
        }
        if (!binding.hasErrors()) {
            try {
                provisioning.provision(
                        form.slug(),
                        form.name(),
                        form.timezone(),
                        form.weekendDays(),
                        null,
                        form.firstAdminEmail(),
                        tenantBaseUrl(form.slug()),
                        currentSuperAdmin().actorId());
                return "redirect:/superadmin/tenants";
            } catch (CaderlyException exception) {
                binding.rejectValue("slug", exception.errorCode(), errorDetail(exception));
            }
        }
        populateTable(model, 0);
        model.addAttribute("openCreatePanel", true);
        return "superadmin/tenants";
    }

    /**
     * Toggles suspension and re-renders the tenant list fragment in place, mirroring {@code
     * web.AdminOrganizationController#deleteDivision}'s own convention exactly: no redirect of any
     * kind, just a 200 response whose body is {@code superadmin/tenants :: content} — the same
     * fragment the row-action button targets via {@code hx-target="#tenant-list-content"
     * hx-swap="outerHTML"}. A genuine Spring {@code redirect:} plus an {@code HX-Redirect} header
     * does *not* work here: per the fetch/XHR spec, htmx's underlying XHR transparently follows a
     * same-origin 3xx redirect before htmx's own response handling ever runs, so htmx would only
     * ever observe the final 200 tenant-list page (which carries no {@code HX-Redirect}) and, with
     * no {@code hx-target}, fall back to swapping that page's body into the button itself. Returning
     * the fragment directly sidesteps the whole redirect-following problem.
     */
    @PatchMapping("/superadmin/tenants/{id}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String toggleSuspend(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        TenantAdminView tenant = requireTenant(id);
        boolean suspending = !tenant.suspended();
        if (suspending) {
            tenants.suspend(id);
        } else {
            tenants.reinstate(id);
        }
        recordTenantAudit(id, Action.UPDATE, Map.of("suspended", suspending));
        populateTable(model, page);
        return "superadmin/tenants :: content";
    }

    /**
     * Uploads or replaces a tenant's logo (ADR 0020). A plain multipart form POST that redirects
     * back with a query flag, like {@link #impersonate}: htmx multipart plumbing buys nothing for a
     * once-per-tenant action. A rejected file surfaces its own {@code error.*} message.
     */
    @PostMapping("/superadmin/tenants/{id}/logo")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String changeLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile file, RedirectAttributes redirect)
            throws IOException {
        requireTenant(id);
        try {
            tenants.changeLogo(id, file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), file.getBytes());
        } catch (CaderlyException exception) {
            redirect.addFlashAttribute("logoError", errorDetail(exception));
            return "redirect:/superadmin/tenants";
        }
        recordTenantAudit(id, Action.UPDATE, Map.of("logo", "changed"));
        redirect.addFlashAttribute("logoUpdated", true);
        return "redirect:/superadmin/tenants";
    }

    @PostMapping("/superadmin/tenants/{id}/logo/remove")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String removeLogo(@PathVariable UUID id, RedirectAttributes redirect) {
        requireTenant(id);
        tenants.clearLogo(id);
        recordTenantAudit(id, Action.UPDATE, Map.of("logo", "removed"));
        redirect.addFlashAttribute("logoUpdated", true);
        return "redirect:/superadmin/tenants";
    }

    /** Same fragment-response convention as {@link #toggleSuspend} — see its Javadoc. */
    @DeleteMapping("/superadmin/tenants/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String delete(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        tenants.softDelete(id);
        recordTenantAudit(id, Action.DELETE, Map.of("deleted", true));
        populateTable(model, page);
        return "superadmin/tenants :: content";
    }

    /**
     * Every Super Admin tenant-lifecycle write is audited here rather than inside {@link
     * TenantFacade}/{@code tenant.TenantService} (CLAUDE.md §5 rule 6): {@code tenant} sits below
     * {@code audit} in the package dependency order ({@code audit.EntityAuditListener} already
     * imports {@code tenant.TenantContext}), so the reverse import would be an ArchUnit-forbidden
     * cycle. This controller already sits above both, so this is the correct, cycle-free place for
     * the write to happen — see {@link TenantProvisioningService#provision} for the create case's
     * identical reasoning.
     */
    private void recordTenantAudit(UUID tenantId, Action action, Map<String, Object> afterFields) {
        auditListener.recordManualEvent(
                tenantId,
                currentSuperAdmin().actorId(),
                "SUPER_ADMIN",
                "Tenant",
                tenantId.toString(),
                action,
                mapper.writeValueAsString(new LinkedHashMap<>(afterFields)));
    }

    /**
     * Mints an impersonation ticket and sends the browser to the tenant's own subdomain to redeem
     * it. A plain, non-htmx {@code <form method="post">} on purpose (see {@code
     * superadmin/tenants.html}): the destination is a different host than this console, and only a
     * real top-level browser navigation follows a cross-origin redirect the way this needs to —
     * an htmx AJAX call to it would be a cross-origin XHR with no CORS policy allowing it.
     *
     * <p>No Admin currently ACTIVE in the tenant is not an error the operator caused, so it is
     * reported the same way a bad or expired ticket is on the tenant side: a redirect back with a
     * query-flag alert, minting nothing (PRD FR-1.8's "no admin-picker UI" MVP simplification — see
     * {@link ImpersonationService#findAnyAdmin}).
     */
    @PostMapping("/superadmin/tenants/{id}/impersonate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    String impersonate(@PathVariable UUID id) {
        TenantAdminView tenant = requireTenant(id);
        Optional<AdminAccount> admin = impersonation.findAnyAdmin(id);
        if (admin.isEmpty()) {
            return "redirect:/superadmin/tenants?impersonateFailed";
        }

        SuperAdminPrincipal current = currentSuperAdmin();
        String token =
                impersonation.mint(current.superAdminId(), current.getUsername(), id, admin.get().userId());

        return "redirect:https://" + tenant.slug() + "." + baseDomain + SecurityPaths.IMPERSONATE_PATH + "?token=" + token;
    }

    /** The page the operator is on; suspend and delete re-render that same page. */
    private void populateTable(Model model, int page) {
        var rows = tenants.listForAdmin(page);
        model.addAttribute("tenantRows", rows.getContent().stream().map(this::toRow).toList());
        model.addAttribute("pageNumber", rows.getNumber());
        model.addAttribute("totalPages", Math.max(rows.getTotalPages(), 1));
        model.addAttribute("hasPrevious", rows.hasPrevious());
        model.addAttribute("hasNext", rows.hasNext());
    }

    /**
     * Employee count is read one tenant at a time under a real, resolved {@code TenantContext} —
     * never {@code runAsSystem} — because {@code employee} is RLS-protected (see this class's
     * Javadoc). {@code TenantContext.clear()} runs in a {@code finally} so a failure counting one
     * tenant's employees can never leave the next row reading under the wrong tenant.
     */
    private TenantRow toRow(TenantAdminView tenant) {
        TenantContext.set(tenant.id());
        long employeeCount;
        try {
            employeeCount = people.countActiveEmployees();
        } finally {
            TenantContext.clear();
        }
        String status = tenant.deletedAt() != null ? "DELETED" : tenant.suspended() ? "SUSPENDED" : "ACTIVE";
        return new TenantRow(tenant.id(), tenant.slug(), tenant.name(), status, employeeCount, tenant.hasLogo());
    }

    private TenantAdminView requireTenant(UUID id) {
        return tenants.find(id).orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "Tenant not found"));
    }

    private static SuperAdminPrincipal currentSuperAdmin() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof SuperAdminPrincipal superAdmin)) {
            // Unreachable through the security chain: this method is @PreAuthorize("hasRole('SUPER_ADMIN')")
            // on a realm whose only authenticated principal type is SuperAdminPrincipal.
            throw new IllegalStateException("Authenticated principal is not a Super Admin");
        }
        return superAdmin;
    }

    /**
     * The new tenant's own origin, not this request's — unlike {@code web.RequestTenant#baseUrl}
     * (package-private in a different package), which builds an invite link from the current
     * request when a tenant Admin invites a colleague from their own subdomain. A Super Admin's
     * request never carries the new tenant's subdomain (it's on the console's own host), so the
     * host must be overridden explicitly rather than copied from the incoming request; scheme and
     * port are still taken from it so this works unchanged under dev's plain HTTP.
     */
    private String tenantBaseUrl(String slug) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .host(slug + "." + baseDomain)
                .build()
                .toUriString();
    }

    /** Mirrors {@code web.WebMessages#errorDetail} (package-private in a different package). */
    private String errorDetail(CaderlyException exception) {
        String key = "error." + exception.errorCode().toLowerCase(Locale.ROOT).replace('_', '-');
        return messages.getMessage(key, null, exception.getMessage(), LocaleContextHolder.getLocale());
    }

    private static TenantCreateForm blankForm() {
        return new TenantCreateForm("", "", "UTC", 96, "");
    }

    record TenantRow(UUID id, String slug, String name, String status, long employeeCount, boolean hasLogo) {}

    record TenantCreateForm(
            @NotBlank(message = "{validation.tenant-create-form.slug.required}")
                    @Size(max = 50, message = "{validation.tenant-create-form.slug.too-long}")
                    @Pattern(
                            regexp = "^[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?$",
                            message = "{validation.tenant-create-form.slug.invalid}")
                    String slug,
            @NotBlank(message = "{validation.tenant-create-form.name.required}")
                    @Size(max = 200, message = "{validation.tenant-create-form.name.too-long}")
                    String name,
            @NotBlank(message = "{validation.tenant-create-form.timezone.required}")
                    @Size(max = 50, message = "{validation.tenant-create-form.timezone.too-long}")
                    String timezone,
            @Min(value = 0, message = "{validation.tenant-create-form.weekend-days.invalid}")
                    @Max(value = 127, message = "{validation.tenant-create-form.weekend-days.invalid}")
                    int weekendDays,
            @NotBlank(message = "{validation.tenant-create-form.first-admin-email.required}")
                    @Email(message = "{validation.tenant-create-form.first-admin-email.invalid}")
                    String firstAdminEmail) {}
}
