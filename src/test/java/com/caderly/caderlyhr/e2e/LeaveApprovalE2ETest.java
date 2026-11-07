package com.caderly.caderlyhr.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.notifications.system.EmailOutbox;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.support.PlaywrightE2ETestBase;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.tenant.TenantRepository;
import com.caderly.caderlyhr.timeoff.LeaveBalance;
import com.caderly.caderlyhr.timeoff.LeaveBalanceRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The headline flow, end to end through a real browser (CLAUDE.md §3, §8): an Employee
 * books time off via the Home dashboard's modal, the request has no manager to route to so it
 * falls back to the tenant's Admin(s) (PRD §12.4 step 2), the Admin approves it from the For
 * Action inbox, and the employee's balance reflects the debit. Mirrors {@code
 * LeaveConfigE2ETest}/{@code EmployeeLifecycleE2ETest}'s helpers rather than re-deriving them.
 */
class LeaveApprovalE2ETest extends PlaywrightE2ETestBase {

    private static final String ADMIN_PASSWORD = "AdminPassphrase1";

    @Autowired private TenantRepository tenants;
    @Autowired private AppUserRepository appUsers;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmployeeRepository employees;
    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveBalanceRepository balances;

    private UUID tenantId;

    @Test
    void employeeBooksTimeOff_adminApproves_balanceUpdatesAndEmailQueued() {
        String slug = "leave-appr-e2e" + UUID.randomUUID().toString().substring(0, 8);
        baseUrl = "http://" + slug + ".localhost:" + port;
        String adminEmail = "admin-" + UUID.randomUUID() + "@example.test";
        String employeeEmail = "employee-" + UUID.randomUUID() + "@example.test";
        seedTenantAndAdmin(slug, adminEmail);

        loginAs(adminEmail, ADMIN_PASSWORD);
        createLeaveType();
        createEmployeeWithHireDate(employeeEmail);

        String rawToken = tokenFromLastEmailTo(employeeEmail);
        acceptInvite(rawToken);
        loginAs(employeeEmail, EMPLOYEE_PASSWORD);

        LocalDate start = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(1); // Monday-Tuesday: two working days, no weekend in range.
        bookTimeOff(start, end);
        page.waitForSelector(".toast-body:has-text('Time off request submitted')");

        // "requested" email queued to the Admin fallback (no manager set on this employee).
        assertThat(emailsQueuedTo(adminEmail, "requested time off")).isNotEmpty();

        logout();
        loginAs(adminEmail, ADMIN_PASSWORD);
        page.navigate(baseUrl + "/for-action");
        page.waitForSelector("button:has-text('Approve')");
        page.click("button:has-text('Approve')");
        page.waitForSelector(".toast-body:has-text('Request approved')");

        // Approve only swaps #pendingList (UI Guidelines §8.6: "row removed via htmx") — the
        // Completed tab's content is populated once, on page load, so a fresh navigation is what
        // picks up the just-decided row.
        page.navigate(baseUrl + "/for-action");
        page.click("#completed-tab");
        page.waitForSelector(".badge:has-text('Approved')");

        TenantContext.set(tenantId);
        UUID employeeId;
        List<LeaveBalance> balance;
        try {
            employeeId =
                    employees.findAllByStatusNot(com.caderly.caderlyhr.people.EmployeeStatus.TERMINATED).stream()
                            .filter(e -> e.email().equals(employeeEmail))
                            .findFirst()
                            .orElseThrow()
                            .requireId();
            balance = balances.findAllByEmployeeIdAndYear(employeeId, start.getYear());
        } finally {
            TenantContext.clear();
        }
        assertThat(balance).hasSize(1);
        assertThat(balance.getFirst().used()).isEqualByComparingTo("2.00");
    }

    /**
     * The Admin needs a real linked {@link Employee}, not just a login: {@code PeopleFacade}'s
     * Admin-fallback lookup (routing a no-manager request) and {@code LeaveApprovalController}'s
     * For Action page both resolve the acting Admin's employee record: the lookup skips an Admin
     * login with none, and For Action shows it a "no employee record" state instead of approvals (see ADR 0010's "Admin login with no linked Employee" note) — a plain
     * {@code AppUser.active(...)} login-only account, as {@code LeaveConfigE2ETest}'s Admin uses,
     * is exactly that case and is not enough here.
     */
    private void seedTenantAndAdmin(String slug, String adminEmail) {
        tenantId =
                TenantContext.runAsSystem(
                        "e2e test: seed tenant", () -> tenants.save(new Tenant(slug, "E2E Co")).getId());

        TenantContext.set(tenantId);
        try {
            Employee admin =
                    employeeService.create(
                            new EmployeeForms.CreateEmployee(
                                    "Ann",
                                    "Admin",
                                    adminEmail,
                                    null, null, null, null, null, null, null,
                                    LocalDate.now(),
                                    null, null, null, null, null, null, null, null),
                            baseUrl,
                            "E2E Co");
            AppUser user = appUsers.findById(admin.userId()).orElseThrow();
            user.grant(Role.ADMIN);
            user.acceptInvite(passwordEncoder.encode(ADMIN_PASSWORD)); // real password, real /login
            appUsers.save(user);
        } finally {
            TenantContext.clear();
        }
    }

    private void createLeaveType() {
        page.navigate(baseUrl + "/admin/leave-types");
        page.click("button:has-text('Add Leave Type')");
        page.waitForSelector("#leave-type-name");
        page.fill("#leave-type-name", "Annual");
        page.fill("#leave-type-defaultAnnualAllowance", "24");
        page.click("#leaveTypeOffcanvasBody button:has-text('Save leave type')");
        page.waitForSelector(".toast-body:has-text('Leave type created')");
    }

    private void createEmployeeWithHireDate(String email) {
        page.navigate(baseUrl + "/admin/employees");
        page.click("button:has-text('Add Employee')");
        page.waitForSelector("#create-firstName");
        page.fill("#create-firstName", "Priya");
        page.fill("#create-lastName", "Shah");
        page.fill("#create-email", email);
        page.fill("#create-hireDate", LocalDate.now().toString());
        page.click("#employeeOffcanvasBody button:has-text('Create')");
        page.waitForSelector(".toast-body:has-text('Employee created')");
    }

    private void bookTimeOff(LocalDate start, LocalDate end) {
        page.navigate(baseUrl + "/");
        page.click("button:has-text('Book time off')");
        page.waitForSelector("#leave-start-date");
        page.click("label:has-text('Annual')");
        page.fill("#leave-start-date", start.toString());
        page.fill("#leave-end-date", end.toString());
        page.click("button:has-text('Submit request')");
    }

    /**
     * Distinct from {@link #tokenFromLastEmailTo(String)} — this asserts an email was queued (by
     * subject substring), it doesn't extract an invite token. Kept as a separate name after the
     * test-suite audit flagged the two as a same-name/different-purpose overload collision.
     */
    private List<EmailOutbox> emailsQueuedTo(String email, String subjectContains) {
        return TenantContext.runAsSystem(
                "e2e test: read outbox",
                () ->
                        outbox.findAll().stream()
                                .filter(row -> row.toEmail().equals(email) && row.subject().contains(subjectContains))
                                .toList());
    }
}
