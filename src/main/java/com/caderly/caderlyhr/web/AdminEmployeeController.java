package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.common.CaderlyException;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.org.OrgFacade;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms.AdminEmployeePatch;
import com.caderly.caderlyhr.people.EmployeeForms.CreateEmployee;
import com.caderly.caderlyhr.people.EmployeeForms.TerminateEmployee;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.people.EmploymentType;
import com.caderly.caderlyhr.tenant.TenantSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin CRUD for Employees (PRD §14, §26: Admin-only). Follows {@code AdminOrganizationController}'s
 * shape: htmx-driven mutations, {@link EmployeeService} owns every write-then-read transaction
 * boundary (ADR 0007), no {@code @Transactional} here (CLAUDE.md §7).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
class AdminEmployeeController {

    private final EmployeeService employees;
    private final OrgFacade orgFacade;
    private final MessageSource messages;
    private final Clock clock;

    AdminEmployeeController(EmployeeService employees, OrgFacade orgFacade, MessageSource messages, Clock clock) {
        this.employees = employees;
        this.orgFacade = orgFacade;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/admin/employees")
    @PreAuthorize("hasRole('ADMIN')")
    String list(
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable EmployeeStatus status,
            @RequestParam(defaultValue = "false") boolean addSelf,
            Model model) {
        model.addAttribute("employees", rows(employees.list(departmentId, status)));
        model.addAttribute("statusOptions", EmployeeStatus.values());
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedStatus", status);
        // From the no-record notice: the page opens Add Employee on load, prefilled for the
        // Admin themselves (newForm's self flag).
        model.addAttribute("addSelf", addSelf);
        // Both offcanvas form fragments render inline on a full-page GET (th:fragment does not
        // suppress that), so both th:object targets need a bound value up front here — same
        // reason AdminOrganizationController#organization populates both divisionForm and
        // departmentForm before either offcanvas is ever opened.
        model.addAttribute("createForm", blankCreateForm());
        model.addAttribute("adminPatch", blankAdminPatch());
        populateFormOptions(model);
        return "people/list";
    }

    @GetMapping("/admin/employees/new-form")
    @PreAuthorize("hasRole('ADMIN')")
    String newForm(
            @RequestParam(defaultValue = "false") boolean self,
            @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        // The Admin's own email comes from their session, never from the URL; saving links the
        // new employee to the login they are signed in with (EmployeeService#create).
        model.addAttribute("createForm", self ? selfCreateForm(principal.getUsername()) : blankCreateForm());
        model.addAttribute("addingSelf", self);
        populateFormOptions(model);
        return "people/list :: createForm";
    }

    @PostMapping("/admin/employees")
    @PreAuthorize("hasRole('ADMIN')")
    String create(
            @Valid @ModelAttribute("createForm") CreateEmployee form,
            BindingResult binding,
            HttpServletRequest request,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                TenantSummary tenant = RequestTenant.of(request);
                Employee created = employees.create(form, RequestTenant.baseUrl(), tenant.name());
                // Only a login that was already active comes back ACTIVE; it got no invite.
                if (created.status() == EmployeeStatus.ACTIVE) {
                    toast(response, "toast.employee.linked", "Employee created and linked to their login");
                } else {
                    toast(response, "toast.employee.created", "Employee created - invite email sent");
                }
                populateContentAttributes(model);
                model.addAttribute("refreshTable", true);
                model.addAttribute("createForm", blankCreateForm());
                populateFormOptions(model);
                return "people/list :: createForm";
            } catch (CaderlyException exception) {
                binding.rejectValue(
                        fieldFor(exception),
                        exception.errorCode(),
                        WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            }
        }
        populateFormOptions(model);
        return "people/list :: createForm";
    }

    @GetMapping("/admin/employees/{id}/edit-form")
    @PreAuthorize("hasRole('ADMIN')")
    String editForm(@PathVariable UUID id, Model model) {
        Employee employee = employees.require(id);
        model.addAttribute("editingEmployeeId", id);
        model.addAttribute("adminPatch", toAdminPatch(employee));
        populateFormOptions(model);
        return "people/list :: editForm";
    }

    @PatchMapping("/admin/employees/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    String update(
            @PathVariable UUID id,
            @Valid @ModelAttribute("adminPatch") AdminEmployeePatch form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            try {
                employees.updateAdminFields(id, form);
                toast(response, "toast.employee.updated", "Employee updated");
                populateContentAttributes(model);
                model.addAttribute("refreshTable", true);
                model.addAttribute("editingEmployeeId", id);
                return "people/list :: editForm";
            } catch (CaderlyException exception) {
                binding.rejectValue(
                        fieldFor(exception),
                        exception.errorCode(),
                        WebMessages.errorDetail(messages, exception, LocaleContextHolder.getLocale()));
            }
        }
        model.addAttribute("editingEmployeeId", id);
        populateFormOptions(model);
        return "people/list :: editForm";
    }

    @PostMapping("/admin/employees/{id}/terminate")
    @PreAuthorize("hasRole('ADMIN')")
    String terminate(
            @PathVariable UUID id,
            @Valid @ModelAttribute("terminateForm") TerminateEmployee form,
            BindingResult binding,
            Model model,
            HttpServletResponse response) {
        if (!binding.hasErrors()) {
            employees.terminate(id, form.terminationDate());
            toast(response, "toast.employee.termination-scheduled", "Employee termination scheduled");
        }
        populateContentAttributes(model);
        model.addAttribute("refreshTable", true);
        return "people/list :: content";
    }

    /** Every response that re-renders the {@code content} fragment needs these (CLAUDE.md §7: no half-rendered fragments). */
    private void populateContentAttributes(Model model) {
        model.addAttribute("employees", rows(employees.list(null, null)));
        model.addAttribute("statusOptions", EmployeeStatus.values());
        model.addAttribute("selectedDepartmentId", null);
        model.addAttribute("selectedStatus", null);
        model.addAttribute("departmentOptions", orgFacade.listActiveDepartments());
    }

    private void populateFormOptions(Model model) {
        model.addAttribute("departmentOptions", orgFacade.listActiveDepartments());
        model.addAttribute("employmentTypeOptions", EmploymentType.values());
        model.addAttribute("managerOptions", rows(employees.list(null, EmployeeStatus.ACTIVE)));
    }

    private static CreateEmployee blankCreateForm() {
        return new CreateEmployee(
                "", "", "", null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /**
     * Today's hire date is prefilled because leave balances are granted on create only when one
     * is set (BalanceService#grantOnHire), and the Admin is usually already working there.
     */
    private CreateEmployee selfCreateForm(String email) {
        return new CreateEmployee(
                "", "", email, null, null, null, null, null, null, null, LocalDate.now(clock), null, null, null,
                null, null, null, null, null);
    }

    private static AdminEmployeePatch blankAdminPatch() {
        return new AdminEmployeePatch(
                null, "", "", "", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static AdminEmployeePatch toAdminPatch(Employee employee) {
        return new AdminEmployeePatch(
                employee.employeeCode(),
                employee.firstName(),
                employee.lastName(),
                employee.email(),
                employee.phone(),
                employee.birthDate(),
                employee.gender(),
                employee.maritalStatus(),
                employee.nationality(),
                employee.citizenship(),
                employee.hireDate(),
                employee.employmentType(),
                employee.department() == null ? null : employee.department().requireId(),
                employee.manager() == null ? null : employee.manager().requireId(),
                employee.jobTitle(),
                employee.workLocation(),
                employee.workingHoursPerDay(),
                employee.currency(),
                employee.baseCompensation());
    }

    /**
     * Routes a service-layer failure to the form field that caused it, so the red message appears
     * under the control the Admin has to change. Mirrors {@code AdminOrganizationController}'s
     * helper of the same name.
     *
     * <p>Errors on the manager or department selects must route to that control, not to {@code
     * email}: a refused reporting-loop error rendered under Email would leave the Manager select
     * looking untouched.
     *
     * <p>{@code EMPLOYEE_NOT_FOUND} maps to the manager select because the manager is the only
     * employee id this form submits — the employee being edited arrives as a path variable, and a
     * bad one there is a 404, not a field error.
     *
     * <p>The default is {@code email} rather than a global error on purpose: it is the one field
     * that is always rendered and always present, so an error code nobody has mapped yet still
     * reaches the user. A global {@code reject} would render nowhere in this template and the
     * save would appear to silently do nothing.
     */
    private static String fieldFor(CaderlyException exception) {
        return switch (exception.errorCode()) {
            case "EMPLOYEE_CANNOT_MANAGE_SELF", "EMPLOYEE_MANAGER_CYCLE", "EMPLOYEE_NOT_FOUND" -> "managerId";
            case "DEPARTMENT_ARCHIVED", "DEPARTMENT_NOT_FOUND" -> "departmentId";
            default -> "email";
        };
    }

    /** Resolves {@code key} through {@code messages.properties} (ADR 0013), then fires the toast. */
    private void toast(HttpServletResponse response, String key, String defaultMessage) {
        String message = messages.getMessage(key, null, defaultMessage, LocaleContextHolder.getLocale());
        response.setHeader("HX-Trigger", WebMessages.toastTrigger(message));
    }

    private static List<EmployeeRow> rows(List<Employee> source) {
        return source.stream()
                .map(
                        e ->
                                new EmployeeRow(
                                        e.requireId(),
                                        e.fullName(),
                                        e.email(),
                                        e.status().name(),
                                        e.status().label(),
                                        e.department() == null ? null : e.department().name(),
                                        e.jobTitle()))
                .toList();
    }

    record EmployeeRow(
            UUID id,
            String fullName,
            String email,
            String status,
            String statusLabel,
            @Nullable String departmentName,
            @Nullable String jobTitle) {}
}
