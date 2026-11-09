package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.audit.AuditAdminService;
import com.caderly.caderlyhr.audit.LoginAuditAdminService;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.InviteService;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeName;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin's audit log at {@code /admin/audit-log} — two tabs, one URL (PRD §24.9's sub-nav names one
 * "Audit Log" entry; §18.3's write-audit columns genuinely differ from login-audit's, which is
 * why this is two tabs on one page rather than one table with columns that don't apply to half its
 * rows).
 *
 * <p>{@code @PreAuthorize} is repeated on every method as well as on the class (ArchitectureTest
 * requires it). No {@code @Transactional} here (CLAUDE.md §7) — both services read {@code
 * TenantContext} themselves, exactly like {@code NotificationAdminService}.
 */
@Controller
@RequestMapping("/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
class AdminAuditController {

    private final AuditAdminService audits;
    private final LoginAuditAdminService logins;
    private final InviteService users;
    private final PeopleFacade people;
    private final TimeoffFacade timeoff;
    private final AuditLogView view;

    AdminAuditController(
            AuditAdminService audits,
            LoginAuditAdminService logins,
            InviteService users,
            PeopleFacade people,
            TimeoffFacade timeoff,
            AuditLogView view) {
        this.audits = audits;
        this.logins = logins;
        this.users = users;
        this.people = people;
        this.timeoff = timeoff;
        this.view = view;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    String page(
            @RequestParam(defaultValue = "writes") String tab,
            @RequestParam(required = false) @Nullable String entityType,
            @RequestParam(required = false) @Nullable Action action,
            @RequestParam(required = false) @Nullable UUID actorUserId,
            @RequestParam(required = false) @Nullable Boolean success,
            @RequestParam(defaultValue = "false") boolean includeSignIns,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        model.addAttribute("activeTab", tab);
        // Only the active tab's data is loaded — Thymeleaf's th:switch never evaluates the
        // attributes the other case would need, so there is nothing to populate for it.
        if ("logins".equals(tab)) {
            addLoginLog(model, success, from, to, page);
        } else {
            addWriteLog(model, entityType, action, actorUserId, from, to, includeSignIns, page);
        }
        return "admin/audit-log";
    }

    @GetMapping("/{id}/diff")
    @PreAuthorize("hasRole('ADMIN')")
    String diff(@PathVariable UUID id, Model model) {
        model.addAttribute("diff", audits.findOne(id));
        return "admin/audit-log :: diff";
    }

    private void addWriteLog(
            Model model,
            @Nullable String entityType,
            @Nullable Action action,
            @Nullable UUID actorUserId,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            boolean includeSignIns,
            int page) {
        var rows = audits.list(entityType, action, actorUserId, from, to, includeSignIns, page);
        // Each lookup is one query for the whole page, never one per row.
        Map<UUID, String> emails = users.listUsers().stream().collect(Collectors.toMap(AppUser::requireId, AppUser::email));
        List<EmployeeName> employees = people.listEmployeeNames();
        var lookups = new AuditLogView.Lookups(
                emails, employees, timeoff.leaveTypeNamesByRecordId(AuditLogView.leaveRecordIds(rows.getContent())));
        model.addAttribute("rows", view.rows(rows.getContent(), lookups));
        model.addAttribute("actorOptions", actorOptions(emails, employees));
        model.addAttribute("kindOptions", view.kindOptions());
        model.addAttribute("includeSignIns", includeSignIns);
        model.addAttribute("pageNumber", rows.getNumber());
        model.addAttribute("totalPages", Math.max(rows.getTotalPages(), 1));
        model.addAttribute("hasPrevious", rows.hasPrevious());
        model.addAttribute("hasNext", rows.hasNext());
        model.addAttribute("actionOptions", List.of(Action.values()));
        model.addAttribute("selectedEntityType", entityType);
        model.addAttribute("selectedAction", action);
        model.addAttribute("selectedActorUserId", actorUserId);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
    }

    /** Every login, by its employee's name where it has one, for the "Person" filter. */
    private static List<AuditLogView.Option> actorOptions(Map<UUID, String> emails, List<EmployeeName> employees) {
        Map<UUID, String> names = new HashMap<>(emails);
        employees.stream().filter(e -> e.userId() != null && names.containsKey(e.userId()))
                .forEach(e -> names.put(e.userId(), e.fullName()));
        return names.entrySet().stream()
                .map(entry -> new AuditLogView.Option(entry.getKey().toString(), entry.getValue()))
                .sorted(Comparator.comparing(AuditLogView.Option::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void addLoginLog(
            Model model, @Nullable Boolean success, @Nullable LocalDate from, @Nullable LocalDate to, int page) {
        var rows = logins.list(success, from, to, page);
        model.addAttribute("loginRows", rows.getContent());
        model.addAttribute("loginPageNumber", rows.getNumber());
        model.addAttribute("loginTotalPages", Math.max(rows.getTotalPages(), 1));
        model.addAttribute("loginHasPrevious", rows.hasPrevious());
        model.addAttribute("loginHasNext", rows.hasNext());
        model.addAttribute("selectedSuccess", success);
        model.addAttribute("selectedLoginFrom", from);
        model.addAttribute("selectedLoginTo", to);
    }
}
