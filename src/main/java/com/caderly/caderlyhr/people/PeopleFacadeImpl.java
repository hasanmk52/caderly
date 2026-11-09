package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.identity.AppUser;
import com.caderly.caderlyhr.identity.AppUserRepository;
import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.identity.UserStatus;
import com.caderly.caderlyhr.org.Department;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PeopleFacadeImpl implements PeopleFacade {

    private final EmployeeRepository employees;
    private final AppUserRepository appUsers;
    private final EmployeeStatusHistoryRepository statusHistory;

    PeopleFacadeImpl(
            EmployeeRepository employees,
            AppUserRepository appUsers,
            EmployeeStatusHistoryRepository statusHistory) {
        this.employees = employees;
        this.appUsers = appUsers;
        this.statusHistory = statusHistory;
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveEmployeesInDepartment(UUID departmentId) {
        return employees.countByDepartmentIdAndStatusNot(departmentId, EmployeeStatus.TERMINATED);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveEmployees() {
        return employees.countByStatusNot(EmployeeStatus.TERMINATED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeHireInfo> listActiveEmployeeHireInfo() {
        return employees.findAllByStatusNot(EmployeeStatus.TERMINATED).stream()
                .map(e -> new EmployeeHireInfo(e.requireId(), e.hireDate()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeHireInfo requireEmployeeHireInfo(UUID employeeId) {
        Employee employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(
                                () -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return new EmployeeHireInfo(employee.requireId(), employee.hireDate());
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeApprovalInfo requireEmployeeApprovalInfo(UUID employeeId) {
        Employee employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(
                                () -> new NotFoundException("EMPLOYEE_NOT_FOUND", "Employee not found"));
        return toApprovalInfo(employee);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeApprovalInfo> listActiveAdminApprovalInfo() {
        return appUsers.findAllByStatus(UserStatus.ACTIVE).stream()
                .filter(user -> user.roles().contains(Role.ADMIN))
                .map(AppUser::requireId)
                .map(employees::findByUserId)
                .flatMap(Optional::stream)
                .map(PeopleFacadeImpl::toApprovalInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isManagerOf(UUID managerId, UUID employeeId) {
        return employees.isManagerOf(managerId, employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findEmployeeIdByUserId(UUID userId) {
        return employees.findByUserId(userId).map(Employee::requireId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeCalendarInfo> listEmployeesForCalendar(
            @Nullable UUID departmentId, @Nullable UUID divisionId) {
        return employees.findActiveForCalendar(departmentId, divisionId).stream()
                .map(PeopleFacadeImpl::toCalendarInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeePeerInfo> listPeers(UUID employeeId) {
        Employee self = employees.findById(employeeId).orElseThrow(() -> new NotFoundException(
                "EMPLOYEE_NOT_FOUND", "Employee not found"));
        UUID departmentId = self.department() == null ? null : self.department().requireId();
        UUID managerId = self.manager() == null ? null : self.manager().requireId();
        if (departmentId == null && managerId == null) {
            return List.of();
        }
        return employees.findPeers(employeeId, departmentId, managerId).stream()
                .map(PeopleFacadeImpl::toPeerInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeName> listEmployeeNames() {
        return employees.findAll().stream()
                .map(e -> new EmployeeName(e.requireId(), e.userId(), e.fullName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeContact> listActiveEmployeeContacts() {
        return employees.findAllByStatusNot(EmployeeStatus.TERMINATED).stream()
                .map(e -> new EmployeeContact(e.requireId(), e.fullName(), e.email()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeReportInfo> listEmployeesForReport(
            @Nullable UUID departmentId, @Nullable UUID divisionId, @Nullable EmployeeStatus status) {
        return employees.findForReport(departmentId, divisionId, status).stream()
                .map(PeopleFacadeImpl::toReportInfo)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonthlyHeadcount> countActiveEmployeesByMonth(
            LocalDate from, LocalDate to, @Nullable UUID departmentId, @Nullable EmploymentType employmentType) {
        List<MonthlyHeadcount> result = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth lastMonth = YearMonth.from(to);
        while (!cursor.isAfter(lastMonth)) {
            LocalDate monthEnd = cursor.atEndOfMonth();
            LocalDate asOf = monthEnd.isAfter(to) ? to : monthEnd;
            for (Object[] row : statusHistory.countActiveByDepartmentAsOf(asOf, employmentType, departmentId)) {
                result.add(new MonthlyHeadcount(asOf, (UUID) row[0], (String) row[1], (Long) row[2]));
            }
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private static EmployeeReportInfo toReportInfo(Employee employee) {
        Department department = employee.department();
        UUID departmentId = department == null ? null : department.requireId();
        String departmentName = department == null ? null : department.name();
        String divisionName = department == null ? null : department.division().name();
        return new EmployeeReportInfo(
                employee.requireId(),
                employee.fullName(),
                departmentId,
                departmentName,
                divisionName,
                employee.status());
    }

    private static EmployeeApprovalInfo toApprovalInfo(Employee employee) {
        UUID managerId = employee.manager() == null ? null : employee.manager().requireId();
        return new EmployeeApprovalInfo(employee.requireId(), employee.fullName(), employee.email(), managerId);
    }

    private static EmployeeCalendarInfo toCalendarInfo(Employee employee) {
        String departmentName = employee.department() == null ? null : employee.department().name();
        return new EmployeeCalendarInfo(employee.requireId(), employee.fullName(), departmentName);
    }

    private static EmployeePeerInfo toPeerInfo(Employee employee) {
        String departmentName = employee.department() == null ? null : employee.department().name();
        return new EmployeePeerInfo(
                employee.requireId(),
                employee.firstName(),
                employee.lastName(),
                employee.fullName(),
                departmentName,
                employee.jobTitle());
    }
}
