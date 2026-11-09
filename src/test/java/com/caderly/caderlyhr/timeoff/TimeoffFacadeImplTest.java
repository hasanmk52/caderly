package com.caderly.caderlyhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The read-side query {@code calendar} depends on: approved-leave-in-range for the team grid,
 * all-approved-for-one-employee for the iCal feed, and holidays-in-range for the grid's shaded
 * columns. Month-boundary overlap cases get their own coverage here.
 */
class TimeoffFacadeImplTest extends TenantIsolationTestBase {

    @Autowired private TimeoffFacade timeoff;
    @Autowired private LeaveTypeRepository leaveTypes;
    @Autowired private LeaveRequestRepository leaveRequests;
    @Autowired private LeaveBalanceRepository leaveBalances;
    @Autowired private PublicHolidayRepository holidays;
    @Autowired private EmployeeRepository employees;

    private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate MONTH_END = LocalDate.of(2026, 9, 30);

    @Test
    void listApprovedLeaveInRange_includesOnlyApprovedRequestsOverlappingTheRange() {
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);

        LeaveRequest withinRange =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, type, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12)));
        asTenant(
                tenantA,
                () -> savePendingRequest(employeeId, type, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16)));
        asTenant(
                tenantA,
                () ->
                        saveApprovedRequest(
                                employeeId, type, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(
                        tenantA,
                        () -> timeoff.listApprovedLeaveInRange(MONTH_START, MONTH_END, List.of(employeeId), null));

        assertThat(entries).extracting(TimeoffFacade.ApprovedLeaveEntry::leaveRequestId)
                .containsExactly(withinRange.requireId());
    }

    @Test
    void listApprovedLeaveInRange_whenRequestSpansIntoPriorMonth_isIncluded() {
        // Started 2026-08-30, ends 2026-09-02: overlaps the visible month at its very first day.
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        LeaveRequest spanning =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, type, LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(
                        tenantA,
                        () -> timeoff.listApprovedLeaveInRange(MONTH_START, MONTH_END, List.of(employeeId), null));

        assertThat(entries).extracting(TimeoffFacade.ApprovedLeaveEntry::leaveRequestId)
                .containsExactly(spanning.requireId());
    }

    @Test
    void listApprovedLeaveInRange_whenRequestSpansIntoNextMonth_isIncluded() {
        // Started 2026-09-29, ends 2026-10-02: overlaps the visible month at its very last day.
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        LeaveRequest spanning =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, type, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(
                        tenantA,
                        () -> timeoff.listApprovedLeaveInRange(MONTH_START, MONTH_END, List.of(employeeId), null));

        assertThat(entries).extracting(TimeoffFacade.ApprovedLeaveEntry::leaveRequestId)
                .containsExactly(spanning.requireId());
    }

    @Test
    void listApprovedLeaveInRange_whenEntirelyOutsideRange_isExcluded() {
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        asTenant(
                tenantA,
                () -> saveApprovedRequest(employeeId, type, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(
                        tenantA,
                        () -> timeoff.listApprovedLeaveInRange(MONTH_START, MONTH_END, List.of(employeeId), null));

        assertThat(entries).isEmpty();
    }

    @Test
    void listApprovedLeaveInRange_whenLeaveTypeIdGiven_filtersToThatType() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        LeaveType sick = asTenant(tenantA, () -> saveLeaveType("Sick"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        asTenant(
                tenantA,
                () ->
                        saveApprovedRequest(
                                employeeId, vacation, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10)));
        LeaveRequest sickRequest =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, sick, LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(
                        tenantA,
                        () ->
                                timeoff.listApprovedLeaveInRange(
                                        MONTH_START, MONTH_END, List.of(employeeId), sick.requireId()));

        assertThat(entries).extracting(TimeoffFacade.ApprovedLeaveEntry::leaveRequestId)
                .containsExactly(sickRequest.requireId());
    }

    @Test
    void listApprovedLeaveInRange_whenEmployeeIdsEmpty_returnsEmptyWithoutQuerying() {
        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(tenantA, () -> timeoff.listApprovedLeaveInRange(MONTH_START, MONTH_END, List.of(), null));

        assertThat(entries).isEmpty();
    }

    @Test
    void listAllApprovedLeaveForEmployee_returnsOnlyThatEmployeesApprovedRequestsSortedByStartDate() {
        LeaveType type = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        UUID otherEmployeeId = asTenant(tenantA, this::saveEmployeeId);

        LeaveRequest later =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, type, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 2)));
        LeaveRequest earlier =
                asTenant(
                        tenantA,
                        () ->
                                saveApprovedRequest(
                                        employeeId, type, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));
        asTenant(
                tenantA,
                () -> savePendingRequest(employeeId, type, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2)));
        asTenant(
                tenantA,
                () ->
                        saveApprovedRequest(
                                otherEmployeeId, type, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));

        List<TimeoffFacade.ApprovedLeaveEntry> entries =
                asTenant(tenantA, () -> timeoff.listAllApprovedLeaveForEmployee(employeeId));

        assertThat(entries).extracting(TimeoffFacade.ApprovedLeaveEntry::leaveRequestId)
                .containsExactly(earlier.requireId(), later.requireId());
    }

    @Test
    void listPublicHolidaysInRange_returnsOnlyHolidaysWithinTheRange() {
        asTenant(tenantA, () -> holidays.save(PublicHoliday.create(LocalDate.of(2026, 9, 15), "Founders Day")));
        asTenant(tenantA, () -> holidays.save(PublicHoliday.create(LocalDate.of(2026, 10, 1), "Other Day")));

        List<TimeoffFacade.HolidayMarker> markers =
                asTenant(tenantA, () -> timeoff.listPublicHolidaysInRange(MONTH_START, MONTH_END));

        assertThat(markers).extracting(TimeoffFacade.HolidayMarker::name).containsExactly("Founders Day");
    }

    @Test
    void currentWeekendDays_decodesTenantsDefaultBitmaskToSaturdaySunday() {
        Set<DayOfWeek> weekend = asTenant(tenantA, () -> timeoff.currentWeekendDays());

        assertThat(weekend).containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void listBalancesForYear_returnsEveryEmployeesBalanceForThatYear() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeA = asTenant(tenantA, this::saveEmployeeId);
        UUID employeeB = asTenant(tenantA, this::saveEmployeeId);
        asTenant(tenantA, () -> leaveBalances.save(LeaveBalance.grant(employeeA, vacation, 2026, new BigDecimal("20"))));
        asTenant(tenantA, () -> leaveBalances.save(LeaveBalance.grant(employeeB, vacation, 2026, new BigDecimal("15"))));
        asTenant(
                tenantA,
                () -> leaveBalances.save(LeaveBalance.grant(employeeA, vacation, 2025, new BigDecimal("20"))));

        List<TimeoffFacade.EmployeeBalanceInfo> result = asTenant(tenantA, () -> timeoff.listBalancesForYear(2026, null));

        assertThat(result).extracting(TimeoffFacade.EmployeeBalanceInfo::employeeId)
                .containsExactlyInAnyOrder(employeeA, employeeB);
    }

    @Test
    void listBalancesForYear_filteredByLeaveType_excludesOtherTypes() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        LeaveType sick = asTenant(tenantA, () -> saveLeaveType("Sick"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        asTenant(tenantA, () -> leaveBalances.save(LeaveBalance.grant(employeeId, vacation, 2026, new BigDecimal("20"))));
        asTenant(tenantA, () -> leaveBalances.save(LeaveBalance.grant(employeeId, sick, 2026, new BigDecimal("10"))));

        List<TimeoffFacade.EmployeeBalanceInfo> result =
                asTenant(tenantA, () -> timeoff.listBalancesForYear(2026, sick.requireId()));

        assertThat(result).extracting(TimeoffFacade.EmployeeBalanceInfo::leaveTypeName).containsExactly("Sick");
    }

    @Test
    void summarizeUtilization_sumsDaysAndCountsRequestsPerEmployeeAndLeaveType() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        asTenant(
                tenantA,
                () -> saveApprovedRequest(employeeId, vacation, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)));
        asTenant(
                tenantA,
                () -> saveApprovedRequest(employeeId, vacation, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)));

        List<TimeoffFacade.UtilizationSummary> result =
                asTenant(
                        tenantA,
                        () ->
                                timeoff.summarizeUtilization(
                                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null));

        assertThat(result).hasSize(1);
        TimeoffFacade.UtilizationSummary summary = result.get(0);
        assertThat(summary.employeeId()).isEqualTo(employeeId);
        assertThat(summary.leaveTypeName()).isEqualTo("Vacation");
        // Each saveApprovedRequest fixture books a fixed 1.00-day duration (see submit()) —
        // 1.00 (single-day request) + 1.00 (two-day request, fixture duration is not
        // computed from the date span) = 2.00.
        assertThat(summary.daysUsed()).isEqualByComparingTo("2.00");
        assertThat(summary.requestCount()).isEqualTo(2L);
    }

    @Test
    void summarizeUtilization_excludesPendingRequestsAndRequestsOutsideRange() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        asTenant(
                tenantA,
                () -> savePendingRequest(employeeId, vacation, LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5)));
        asTenant(
                tenantA,
                () ->
                        saveApprovedRequest(
                                employeeId, vacation, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1)));

        List<TimeoffFacade.UtilizationSummary> result =
                asTenant(
                        tenantA,
                        () ->
                                timeoff.summarizeUtilization(
                                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null));

        assertThat(result).isEmpty();
    }

    // leave_request.employee_id carries a real FK to employee(id) (V202608171000), even though
    // the JPA entity models it as a plain UUID (timeoff never navigates a people.Employee
    // relation) — so tests need an actual saved Employee row, not an arbitrary UUID.
    @Test
    void leaveTypeNamesByRecordId_resolvesBalancesAndRequests_onlyInTheCurrentTenant() {
        LeaveType vacation = asTenant(tenantA, () -> saveLeaveType("Vacation"));
        LeaveType sick = asTenant(tenantA, () -> saveLeaveType("Sick"));
        UUID employeeId = asTenant(tenantA, this::saveEmployeeId);
        UUID balanceId = asTenant(tenantA, () -> leaveBalances.save(
                LeaveBalance.grant(employeeId, vacation, 2026, new BigDecimal("20"))).requireId());
        UUID requestId = asTenant(tenantA, () -> savePendingRequest(
                employeeId, sick, MONTH_START.plusDays(7), MONTH_START.plusDays(8)).requireId());
        UUID unknown = UUID.randomUUID();

        assertThat(asTenant(tenantA, () -> timeoff.leaveTypeNamesByRecordId(java.util.List.of(balanceId, requestId, unknown))))
                .containsOnly(java.util.Map.entry(balanceId, "Vacation"), java.util.Map.entry(requestId, "Sick"));
        assertThat(asTenant(tenantB, () -> timeoff.leaveTypeNamesByRecordId(java.util.List.of(balanceId, requestId))))
                .isEmpty();
    }

    private UUID saveEmployeeId() {
        return employees
                .save(Employee.create("Test", "Employee", "employee-" + UUID.randomUUID() + "@example.test"))
                .requireId();
    }

    private LeaveType saveLeaveType(String name) {
        return leaveTypes.save(
                LeaveType.create(
                        name, "bi-airplane", "#0d6efd", true, true, false, true, new BigDecimal("20"), null));
    }

    private LeaveRequest saveApprovedRequest(
            UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        LeaveRequest request = submit(employeeId, type, start, end);
        // decider_id has a real FK to app_user(id); null is allowed and simpler than seeding a
        // decider account these tests don't otherwise need.
        request.approve(null, null, Instant.now());
        return leaveRequests.save(request);
    }

    private LeaveRequest savePendingRequest(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        return leaveRequests.save(submit(employeeId, type, start, end));
    }

    private static LeaveRequest submit(UUID employeeId, LeaveType type, LocalDate start, LocalDate end) {
        return LeaveRequest.submit(
                employeeId, type, start, end, false, false, new BigDecimal("1.00"), null, Instant.now());
    }
}
