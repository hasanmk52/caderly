package com.caderly.caderlyhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.ValidationException;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeForms;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.support.MutableClock;
import com.caderly.caderlyhr.support.MutableClockConfiguration;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Proves the three PRD §12.2 grant triggers (on-hire, on-leave-type-activation, annual) share
 * correct pro-rate math and are idempotent — CLAUDE.md §8's "critical logic, TDD first" category.
 */
@Import(MutableClockConfiguration.class)
class BalanceServiceTest extends TenantIsolationTestBase {

    private static final String BASE_URL = "https://acme.localhost";
    private static final String TENANT_NAME = "Acme";

    @Autowired private EmployeeService employeeService;
    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveBalanceRepository balances;
    @Autowired private BalanceService balanceService;
    @Autowired private MutableClock clock;

    @Test
    void grantOnHire_julyHireWithThirtyDayAllowance_grantsFifteenDays() {
        // PRD §12.2 worked example: hired July 1 with a 30-day annual allowance -> 15 days.
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 7, 1)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().granted()).isEqualByComparingTo("15.00");
    }

    @Test
    void grantOnHire_janOneHireWithThirtyDayAllowance_grantsFullThirtyDays() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 1, 1)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result.getFirst().granted()).isEqualByComparingTo("30.00");
    }

    @Test
    void grantOnHire_decThirtyFirstHireWithThirtyDayAllowance_grantsHalfDay() {
        // Dec (month 12): monthsRemaining = 1 -> 30 * 1/12 = 2.5, rounded to nearest 0.5 already.
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 12, 31)), BASE_URL, TENANT_NAME));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result.getFirst().granted()).isEqualByComparingTo("2.50");
    }

    @Test
    void grantOnHire_historicalHireDateFromAPastYear_grantsFullAllowanceForTheCurrentYear() {
        // An existing employee entered with their real historical hire date (e.g. an HRIS
        // migration) — must grant a full current-year balance, not a stale grant for a year
        // that has already ended (which nothing ever reads: the dashboard only queries the
        // current year via listCurrentYearForEmployee).
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));

        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2018, 4, 1)), BASE_URL, TENANT_NAME));

        int currentYear = LocalDate.now(clock).getYear();
        List<LeaveBalance> currentYearResult =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), currentYear));
        assertThat(currentYearResult).hasSize(1);
        assertThat(currentYearResult.getFirst().granted()).isEqualByComparingTo("30.00");

        List<LeaveBalance> hireYearResult =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2018));
        assertThat(hireYearResult).isEmpty();
    }

    @Test
    void grantOnHire_firedTwiceForSameEmployee_doesNotDoubleTheBalance() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2026, 7, 1)), BASE_URL, TENANT_NAME));

        // EmployeeService.create already fired the grant once via EmployeeHiredEvent; fire again
        // directly to prove the idempotency check, not just that it only ever fires once.
        asTenant(
                tenantA,
                () -> {
                    balanceService.grantOnHire(employee.requireId());
                    return null;
                });

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result).hasSize(1);
    }

    @Test
    void grantOnHire_hireDateAddedAfterCreate_grantsTheProRatedBalanceThenOnly() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee =
                asTenant(tenantA, () -> employeeService.create(createEmployeeForm(null), BASE_URL, TENANT_NAME));
        assertThat(asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026)))
                .isEmpty();

        asTenant(tenantA, () -> employeeService.updateAdminFields(employee.requireId(), hireDatePatch(employee, LocalDate.of(2026, 7, 1))));
        // A later correction leaves the granted balance alone; the Admin adjusts it by hand.
        asTenant(tenantA, () -> employeeService.updateAdminFields(employee.requireId(), hireDatePatch(employee, LocalDate.of(2026, 3, 1))));

        List<LeaveBalance> result =
                asTenant(tenantA, () -> balances.findAllByEmployeeIdAndYear(employee.requireId(), 2026));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().granted()).isEqualByComparingTo("15.00");
    }

    @Test
    void grantOnLeaveTypeActivation_backfillsExistingActiveEmployees() {
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        // No leave types existed at hire time, so no balance yet.
        assertThat(
                        asTenant(
                                tenantA,
                                () ->
                                        balances.findAllByEmployeeIdAndYear(
                                                employee.requireId(), LocalDate.now(clock).getYear())))
                .isEmpty();

        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Sick", null, null, true, false, false, true, new BigDecimal("10"), null));

        List<LeaveBalance> result =
                asTenant(
                        tenantA,
                        () ->
                                balances.findAllByEmployeeIdAndYear(
                                        employee.requireId(), LocalDate.now(clock).getYear()));
        assertThat(result).hasSize(1);
    }

    @Test
    void grantAnnual_runTwiceForSameYear_secondRunGrantsNothingNew() {
        asTenant(
                tenantA,
                () ->
                        leaveTypeService.create(
                                "Annual", null, null, true, true, false, true, new BigDecimal("24"), null));
        asTenant(
                tenantA,
                () ->
                        employeeService.create(
                                createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));

        int first = asTenant(tenantA, () -> balanceService.grantAnnual(2027));
        int second = asTenant(tenantA, () -> balanceService.grantAnnual(2027));

        assertThat(first).isGreaterThan(0);
        assertThat(second).isZero();
    }

    // Tenant isolation for grantAnnual is covered more thoroughly by
    // AnnualGrantJobTest (two tenants, two different allowances, asserting each tenant's
    // grant reflects only its own leave type) and by TimeoffTenantIsolationTest (the general
    // RLS mechanism for leave_balance) — no need to re-prove it a third time here.

    @Test
    void bookUsed_incrementsUsed() {
        LeaveTypeAndEmployee fixture = fullYearAnnualBalance();

        asTenant(tenantA, () -> balanceService.bookUsed(
                fixture.employeeId(), fixture.leaveTypeId(), fixture.year(), new BigDecimal("5")));

        assertThat(currentBalance(fixture).used()).isEqualByComparingTo("5.00");
    }

    @Test
    void releaseUsed_decrementsUsed() {
        LeaveTypeAndEmployee fixture = fullYearAnnualBalance();
        asTenant(tenantA, () -> balanceService.bookUsed(
                fixture.employeeId(), fixture.leaveTypeId(), fixture.year(), new BigDecimal("5")));

        asTenant(tenantA, () -> balanceService.releaseUsed(
                fixture.employeeId(), fixture.leaveTypeId(), fixture.year(), new BigDecimal("2")));

        assertThat(currentBalance(fixture).used()).isEqualByComparingTo("3.00");
    }

    @Test
    void adjustManually_whenNewGrantedBelowUsed_throwsValidationException() {
        // M7 (post-1.5 review): reject pushing granted below used, backstopped by the DB CHECK.
        LeaveTypeAndEmployee fixture = fullYearAnnualBalance();
        asTenant(tenantA, () -> balanceService.bookUsed(
                fixture.employeeId(), fixture.leaveTypeId(), fixture.year(), new BigDecimal("10")));

        assertThatThrownBy(
                        () ->
                                asTenant(
                                        tenantA,
                                        () ->
                                                balanceService.adjustManually(
                                                        fixture.employeeId(),
                                                        fixture.leaveTypeId(),
                                                        new BigDecimal("5"),
                                                        "correction",
                                                        "admin@acme.test")))
                .isInstanceOf(ValidationException.class)
                .extracting(exception -> ((ValidationException) exception).errorCode())
                .isEqualTo("LEAVE_BALANCE_BELOW_USED");
    }

    @Test
    void adjustManually_whenAtOrAboveUsed_succeeds() {
        LeaveTypeAndEmployee fixture = fullYearAnnualBalance();
        asTenant(tenantA, () -> balanceService.bookUsed(
                fixture.employeeId(), fixture.leaveTypeId(), fixture.year(), new BigDecimal("10")));

        asTenant(
                tenantA,
                () ->
                        balanceService.adjustManually(
                                fixture.employeeId(),
                                fixture.leaveTypeId(),
                                new BigDecimal("10"),
                                "correction",
                                "admin@acme.test"));

        assertThat(currentBalance(fixture).granted()).isEqualByComparingTo("10.00");
    }

    /** Historical hire date (2020) always grants a full-year balance for the current test-clock year. */
    private LeaveTypeAndEmployee fullYearAnnualBalance() {
        LeaveType type =
                asTenant(
                        tenantA,
                        () ->
                                leaveTypeService.create(
                                        "Annual", null, null, true, true, false, true, new BigDecimal("30"), null));
        Employee employee =
                asTenant(
                        tenantA,
                        () ->
                                employeeService.create(
                                        createEmployeeForm(LocalDate.of(2020, 1, 1)), BASE_URL, TENANT_NAME));
        int year = LocalDate.now(clock).getYear();
        return new LeaveTypeAndEmployee(employee.requireId(), type.requireId(), year);
    }

    private LeaveBalance currentBalance(LeaveTypeAndEmployee fixture) {
        return asTenant(
                tenantA,
                () ->
                        balances
                                .findByEmployeeIdAndLeaveTypeIdAndYear(
                                        fixture.employeeId(), fixture.leaveTypeId(), fixture.year())
                                .orElseThrow());
    }

    private record LeaveTypeAndEmployee(UUID employeeId, UUID leaveTypeId, int year) {}

    private static EmployeeForms.AdminEmployeePatch hireDatePatch(Employee employee, LocalDate hireDate) {
        return new EmployeeForms.AdminEmployeePatch(
                null, employee.firstName(), employee.lastName(), employee.email(), null, null, null, null, null,
                null, hireDate, null, null, null, null, null, BigDecimal.valueOf(8), null, null);
    }

    private EmployeeForms.CreateEmployee createEmployeeForm(@Nullable LocalDate hireDate) {
        return new EmployeeForms.CreateEmployee(
                "Priya",
                "Shah",
                UUID.randomUUID() + "@example.test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                hireDate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
