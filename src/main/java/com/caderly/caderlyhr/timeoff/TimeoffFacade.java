package com.caderly.caderlyhr.timeoff;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of approved leave and public holidays for other modules (CLAUDE.md §4). First
 * consumer is {@code calendar}: the team calendar grid and the per-user iCal feed
 * both need approved leave without any of {@code timeoff}'s write-side state machine or balance
 * math.
 */
public interface TimeoffFacade {

    /**
     * APPROVED leave for the given employees overlapping [from, to] (inclusive), optionally
     * narrowed to one leave type — the team calendar grid's query (PRD §6.6 FR-6.1/FR-6.2).
     */
    List<ApprovedLeaveEntry> listApprovedLeaveInRange(
            LocalDate from, LocalDate to, List<UUID> employeeIds, @Nullable UUID leaveTypeId);

    /**
     * Every APPROVED leave request for one employee, unbounded — the iCal feed's full scope (PRD
     * AC-CALENDAR.1: "all my APPROVED leaves").
     */
    List<ApprovedLeaveEntry> listAllApprovedLeaveForEmployee(UUID employeeId);

    /** Public holidays overlapping [from, to] — the grid's shaded holiday columns (UI §8.4). */
    List<HolidayMarker> listPublicHolidaysInRange(LocalDate from, LocalDate to);

    /**
     * The current tenant's configured weekend days (PRD §12.5, §21), decoded from {@code
     * tenant.Tenant.weekendDays}' bitmask — the same source {@code LeaveRequestService} uses for
     * duration math, so the calendar grid's weekend shading can never disagree with it.
     */
    Set<DayOfWeek> currentWeekendDays();

    /**
     * Every employee's balance for {@code year}, optionally narrowed to one leave type —
     * {@code reports.ReportService}'s Leave Balance report (PRD §16.1). Unlike {@code
     * LeaveBalanceRepository#findAllByEmployeeIdAndYear}, this is not scoped to one employee: the
     * report joins the result against {@code PeopleFacade.listEmployeesForReport} by employee id.
     */
    List<EmployeeBalanceInfo> listBalancesForYear(int year, @Nullable UUID leaveTypeId);

    /**
     * Per (employee, leave type) totals for APPROVED requests starting in {@code [from, to]} —
     * {@code reports.ReportService}'s Leave Utilization report (PRD §16.1 FR-10.2). The
     * aggregation (SUM of {@code durationDays}, COUNT of requests) happens here, inside the
     * module that owns {@code LeaveRequest}, not in {@code reports} (ADR 0018) — the codebase's
     * first GROUP BY query.
     */
    List<UtilizationSummary> summarizeUtilization(LocalDate from, LocalDate to, @Nullable UUID leaveTypeId);

    /**
     * The leave type of each given balance or request id that still exists in this tenant — the
     * audit log's labels need it because an audit snapshot leaves out the {@code leaveType}
     * association. Ids that match nothing are simply absent from the map.
     */
    Map<UUID, String> leaveTypeNamesByRecordId(Collection<UUID> recordIds);

    record EmployeeBalanceInfo(
            UUID employeeId,
            UUID leaveTypeId,
            String leaveTypeName,
            BigDecimal granted,
            BigDecimal used,
            BigDecimal remaining) {}

    record UtilizationSummary(
            UUID employeeId, UUID leaveTypeId, String leaveTypeName, BigDecimal daysUsed, long requestCount) {}

    record ApprovedLeaveEntry(
            UUID employeeId,
            UUID leaveRequestId,
            String leaveTypeName,
            @Nullable String leaveTypeIcon,
            LocalDate startDate,
            LocalDate endDate,
            boolean startHalfDayPm,
            boolean endHalfDayAm,
            BigDecimal durationDays) {}

    record HolidayMarker(LocalDate date, String name) {}
}
