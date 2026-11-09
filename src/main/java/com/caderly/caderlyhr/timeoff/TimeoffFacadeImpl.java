package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.tenant.TenantFacade;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TimeoffFacadeImpl implements TimeoffFacade {

    private final LeaveRequestRepository leaveRequests;
    private final LeaveBalanceRepository leaveBalances;
    private final PublicHolidayRepository holidays;
    private final TenantFacade tenants;

    TimeoffFacadeImpl(
            LeaveRequestRepository leaveRequests,
            LeaveBalanceRepository leaveBalances,
            PublicHolidayRepository holidays,
            TenantFacade tenants) {
        this.leaveRequests = leaveRequests;
        this.leaveBalances = leaveBalances;
        this.holidays = holidays;
        this.tenants = tenants;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovedLeaveEntry> listApprovedLeaveInRange(
            LocalDate from, LocalDate to, List<UUID> employeeIds, @Nullable UUID leaveTypeId) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return leaveRequests.findApprovedInRangeForEmployees(employeeIds, from, to, leaveTypeId).stream()
                .map(TimeoffFacadeImpl::toEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovedLeaveEntry> listAllApprovedLeaveForEmployee(UUID employeeId) {
        return leaveRequests
                .findAllByEmployeeIdAndStatusOrderByStartDateAsc(employeeId, LeaveRequestStatus.APPROVED)
                .stream()
                .map(TimeoffFacadeImpl::toEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HolidayMarker> listPublicHolidaysInRange(LocalDate from, LocalDate to) {
        return holidays.findAllByOrderByDateAsc().stream()
                .filter(h -> !h.date().isBefore(from) && !h.date().isAfter(to))
                .map(h -> new HolidayMarker(h.date(), h.name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<DayOfWeek> currentWeekendDays() {
        return LeaveDurationCalculator.decodeWeekend(tenants.currentWeekendDays());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeBalanceInfo> listBalancesForYear(int year, @Nullable UUID leaveTypeId) {
        return leaveBalances.findAllByYear(year).stream()
                .filter(balance -> leaveTypeId == null || leaveTypeId.equals(balance.leaveType().requireId()))
                .map(
                        balance ->
                                new EmployeeBalanceInfo(
                                        balance.employeeId(),
                                        balance.leaveType().requireId(),
                                        balance.leaveType().name(),
                                        balance.granted(),
                                        balance.used(),
                                        balance.remaining()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UtilizationSummary> summarizeUtilization(
            LocalDate from, LocalDate to, @Nullable UUID leaveTypeId) {
        return leaveRequests.summarizeUtilization(from, to, leaveTypeId).stream()
                .map(
                        row ->
                                new UtilizationSummary(
                                        (UUID) row[0],
                                        (UUID) row[1],
                                        (String) row[2],
                                        (BigDecimal) row[3],
                                        (Long) row[4]))
                .toList();
    }

    private static ApprovedLeaveEntry toEntry(LeaveRequest request) {
        LeaveType type = request.leaveType();
        return new ApprovedLeaveEntry(
                request.employeeId(),
                request.requireId(),
                type.name(),
                type.icon(),
                request.startDate(),
                request.endDate(),
                request.startHalfDayPm(),
                request.endHalfDayAm(),
                request.durationDays());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> leaveTypeNamesByRecordId(Collection<UUID> recordIds) {
        Map<UUID, String> names = new HashMap<>();
        if (recordIds.isEmpty()) {
            return names;
        }
        leaveBalances.findAllById(recordIds).forEach(b -> names.put(b.requireId(), b.leaveType().name()));
        leaveRequests.findAllById(recordIds).forEach(r -> names.put(r.requireId(), r.leaveType().name()));
        return names;
    }
}
