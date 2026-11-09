package com.caderly.caderlyhr.people;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of Employee data for other modules (CLAUDE.md §4). Original consumer is {@code
 * web.AdminOrganizationController}, which needs an employee count to decide whether deleting a
 * Department should archive it instead (PRD §6.4 FR-4.2). {@code timeoff} is the second
 * consumer — its balance-grant jobs need to iterate active employees;
 * {@code timeoff} never depends back on {@code people} for anything else, and {@code people}
 * never imports {@code timeoff} at all (see {@link EmployeeHiredEvent}).
 *
 * <p>Deliberately not consumed by {@code org} itself: {@code org} already flows into {@code
 * people} via {@code OrgFacade}, so {@code org} depending back on {@code people} would be a
 * package cycle ArchUnit rejects. The orchestration lives one layer up instead.
 */
public interface PeopleFacade {

    /** Employees in this department whose status isn't TERMINATED. */
    long countActiveEmployeesInDepartment(UUID departmentId);

    /**
     * Every employee in the current tenant whose status isn't TERMINATED — the Super Admin
     * console's per-tenant employee count. Same convention as {@link
     * #countActiveEmployeesInDepartment}, minus the department filter.
     */
    long countActiveEmployees();

    /**
     * Employees whose status isn't TERMINATED, for {@code timeoff.BalanceService}'s annual grant
     * job and leave-type-activation backfill (PRD §12.2). {@code hireDate} is nullable exactly as
     * it is on {@link Employee} itself — callers must decide what to do with an employee who has
     * none.
     */
    List<EmployeeHireInfo> listActiveEmployeeHireInfo();

    /**
     * @throws com.caderly.caderlyhr.common.NotFoundException if the employee doesn't exist. Backs the
     *     on-hire grant ({@link EmployeeHiredEvent}).
     */
    EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId);

    /**
     * Approval routing info for {@code timeoff.LeaveRequestService} (PRD §12.4 steps 2-3):
     * booking looks up the requester's own row (for {@code managerId}) and, when routing to a
     * manager, the manager's own row (for name/email to put in the notification).
     *
     * @throws com.caderly.caderlyhr.common.NotFoundException if the employee doesn't exist.
     */
    EmployeeApprovalInfo requireEmployeeApprovalInfo(UUID employeeId);

    /**
     * Every ACTIVE login holding {@code Role.ADMIN}, resolved to their linked Employee (PRD §12.4
     * step 2's "Admin(s)" fallback when a requester has no manager). An Admin login with no linked
     * Employee (e.g. a dev-bootstrap account) is silently skipped — there is no employee identity
     * to notify or to check {@code isManagerOf} against.
     */
    List<EmployeeApprovalInfo> listActiveAdminApprovalInfo();

    /**
     * Whether {@code managerId} is a direct or indirect manager of {@code employeeId} (PRD §26:
     * Manager approval authority is transitive). Delegates straight to {@code
     * EmployeeRepository#isManagerOf}, the same recursive-CTE query {@code
     * EmployeeService#getProfileForViewer} already relies on.
     */
    boolean isManagerOf(UUID managerId, UUID employeeId);

    /**
     * Resolves an {@code identity.AppUser} id to the Employee record it's linked to, for {@code
     * calendar.CalendarFeedService} (token owner -> whose leave to render) and Settings ->
     * Calendar integration. Delegates to {@code EmployeeRepository#findByUserId}, already used
     * internally by {@link #listActiveAdminApprovalInfo} but not previously exposed here.
     */
    Optional<UUID> findEmployeeIdByUserId(UUID userId);

    /**
     * Not-terminated employees for the team calendar grid (PRD §6.6 FR-6.2), optionally narrowed
     * to one department and/or division. Either or both filters may be {@code null}.
     */
    List<EmployeeCalendarInfo> listEmployeesForCalendar(
            @Nullable UUID departmentId, @Nullable UUID divisionId);

    /**
     * Same-department or same-manager peers of {@code employeeId}, excluding the employee itself
     * and anyone terminated (PRD §24.2 "My Peers" widget). An employee with neither
     * a department nor a manager has no peers.
     */
    List<EmployeePeerInfo> listPeers(UUID employeeId);

    /**
     * Every not-terminated employee's name and work address — the recipient list for a
     * tenant-wide announcement, currently {@code timeoff.HolidayReminderJob} (PRD §17.2, "Public
     * holiday tomorrow → all active users").
     *
     * <p>Sourced from {@code employee}, not {@code app_user}: an employee with no login still
     * needs to know the office is closed, and their work address is on their employee record.
     */
    List<EmployeeContact> listActiveEmployeeContacts();

    /**
     * Every employee (any status), with their current department/division name, for {@code
     * reports.ReportService}'s Leave Balance report (PRD §16.1) — the report needs INVITED and
     * TERMINATED rows too when {@code status} is left {@code null}, unlike every other consumer
     * of this facade, which excludes terminated employees by default. All three filters are
     * optional and independent.
     */
    List<EmployeeReportInfo> listEmployeesForReport(
            @Nullable UUID departmentId, @Nullable UUID divisionId, @Nullable EmployeeStatus status);

    /**
     * One row per (month-end, department) in {@code [from, to]} with the count of employees
     * ACTIVE as of that month-end — {@code reports.ReportService}'s Headcount report (PRD §16.1
     * FR-10.3). Built from {@link EmployeeStatusHistory}, the only place a past-in-time status is
     * recoverable ({@link Employee#status()} is current-state only).
     *
     * <p>Department grouping uses each employee's <em>current</em> department, not whatever
     * department they were in during that historical month: no historical department tracking
     * exists (only {@code EmployeeManagerHistory} tracks manager reassignment) — a documented
     * simplification, not a bug (ADR 0018).
     */
    List<MonthlyHeadcount> countActiveEmployeesByMonth(
            LocalDate from, LocalDate to, @Nullable UUID departmentId, @Nullable EmploymentType employmentType);

    /**
     * Every employee's name, any status, with the login it is linked to — {@code web}'s audit log
     * names actors and records with it, and history must still name someone who has left.
     */
    List<EmployeeName> listEmployeeNames();

    record EmployeeContact(UUID employeeId, String fullName, String email) {}

    record EmployeeName(UUID employeeId, @Nullable UUID userId, String fullName) {}

    record EmployeeReportInfo(
            UUID employeeId,
            String fullName,
            @Nullable UUID departmentId,
            @Nullable String departmentName,
            @Nullable String divisionName,
            EmployeeStatus status) {}

    /** {@code month} is always the last day of its calendar month. */
    record MonthlyHeadcount(
            LocalDate month, @Nullable UUID departmentId, @Nullable String departmentName, long activeCount) {}

    record EmployeeHireInfo(UUID employeeId, @Nullable LocalDate hireDate) {}

    record EmployeeApprovalInfo(
            UUID employeeId, String fullName, String email, @Nullable UUID managerId) {}

    record EmployeeCalendarInfo(
            UUID employeeId, String fullName, @Nullable String departmentName) {}

    record EmployeePeerInfo(
            UUID employeeId,
            String firstName,
            String lastName,
            String fullName,
            @Nullable String departmentName,
            @Nullable String jobTitle) {}
}
