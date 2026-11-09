package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.org.OrgFacade;
import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.people.EmploymentType;
import com.caderly.caderlyhr.reports.ReportCsvWriter;
import com.caderly.caderlyhr.reports.ReportService;
import com.caderly.caderlyhr.reports.ReportService.HeadcountRow;
import com.caderly.caderlyhr.reports.ReportService.LeaveBalanceRow;
import com.caderly.caderlyhr.reports.ReportService.UtilizationRow;
import com.caderly.caderlyhr.timeoff.LeaveType;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin → Reports (PRD §16.1, §26 "Generate reports" is Admin-only): Leave Balance,
 * Leave Utilization, and Headcount, each with an on-screen preview and a CSV download of the
 * exact same filtered rows.
 *
 * <p>{@code @PreAuthorize} is repeated on every method as well as on the class, which {@code
 * ArchitectureTest} requires. Downloads live here, under {@code /admin/reports/*.csv}, rather
 * than under {@code /api/v1} (PRD §23.2's literal path) — this is an internal Admin-only download
 * link with a session cookie, not an externally-consumed API (ADR 0018).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
class AdminReportsController {

    private final ReportService reports;
    private final OrgFacade org;
    private final LeaveTypeService leaveTypes;
    private final Clock clock;
    private final MessageSource messages;

    AdminReportsController(
            ReportService reports, OrgFacade org, LeaveTypeService leaveTypes, Clock clock, MessageSource messages) {
        this.reports = reports;
        this.org = org;
        this.leaveTypes = leaveTypes;
        this.clock = clock;
        this.messages = messages;
    }

    @GetMapping("/admin/reports")
    @PreAuthorize("hasRole('ADMIN')")
    String landing() {
        return "admin/reports";
    }

    @GetMapping("/admin/reports/leave-balance")
    @PreAuthorize("hasRole('ADMIN')")
    String leaveBalancePage(
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable UUID divisionId,
            @RequestParam(required = false) @Nullable EmployeeStatus status,
            @RequestParam(required = false) @Nullable UUID leaveTypeId,
            @RequestParam(required = false) @Nullable Integer year,
            Model model) {
        int effectiveYear = year != null ? year : LocalDate.now(clock).getYear();
        List<LeaveBalanceRow> rows =
                reports.leaveBalanceReport(departmentId, divisionId, status, leaveTypeId, effectiveYear);
        addOrgAndLeaveTypeOptions(model);
        model.addAttribute("statusOptions", EmployeeStatus.values());
        model.addAttribute("rows", rows);
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedDivisionId", divisionId);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedLeaveTypeId", leaveTypeId);
        model.addAttribute("selectedYear", effectiveYear);
        return "admin/reports-leave-balance";
    }

    @GetMapping("/admin/reports/leave-balance.csv")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<byte[]> leaveBalanceCsv(
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable UUID divisionId,
            @RequestParam(required = false) @Nullable EmployeeStatus status,
            @RequestParam(required = false) @Nullable UUID leaveTypeId,
            @RequestParam(required = false) @Nullable Integer year) {
        int effectiveYear = year != null ? year : LocalDate.now(clock).getYear();
        List<LeaveBalanceRow> rows =
                reports.leaveBalanceReport(departmentId, divisionId, status, leaveTypeId, effectiveYear);
        byte[] csv =
                ReportCsvWriter.write(
                        new String[] {"Employee", "Department", "Leave Type", "Granted", "Used", "Remaining"},
                        rows.stream()
                                .map(
                                        row ->
                                                new String[] {
                                                    row.employeeName(),
                                                    row.departmentName() == null ? "" : row.departmentName(),
                                                    row.leaveTypeName(),
                                                    row.granted().toPlainString(),
                                                    row.used().toPlainString(),
                                                    row.remaining().toPlainString()
                                                })
                                .toList());
        return csvResponse(csv, "leave-balance-" + effectiveYear + ".csv");
    }

    @GetMapping("/admin/reports/leave-utilization")
    @PreAuthorize("hasRole('ADMIN')")
    String leaveUtilizationPage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable UUID leaveTypeId,
            Model model) {
        LocalDate effectiveFrom = from != null ? from : defaultYearStart();
        LocalDate effectiveTo = to != null ? to : defaultYearEnd();
        List<UtilizationRow> rows =
                reports.leaveUtilizationReport(effectiveFrom, effectiveTo, departmentId, leaveTypeId);
        addOrgAndLeaveTypeOptions(model);
        model.addAttribute("rows", rows);
        // Colours follow the tenant's full leave-type list, not the filtered rows (PRD FR-10.2).
        model.addAttribute(
                "chart",
                ReportCharts.utilization(
                        rows,
                        leaveTypes.listAll().stream().map(LeaveType::name).toList(),
                        messages.getMessage("admin.reports.chart.no-department", null, LocaleContextHolder.getLocale()),
                        messages.getMessage("admin.reports.chart.other", null, LocaleContextHolder.getLocale())));
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedLeaveTypeId", leaveTypeId);
        model.addAttribute("selectedFrom", effectiveFrom);
        model.addAttribute("selectedTo", effectiveTo);
        return "admin/reports-leave-utilization";
    }

    @GetMapping("/admin/reports/leave-utilization.csv")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<byte[]> leaveUtilizationCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable UUID leaveTypeId) {
        LocalDate effectiveFrom = from != null ? from : defaultYearStart();
        LocalDate effectiveTo = to != null ? to : defaultYearEnd();
        List<UtilizationRow> rows =
                reports.leaveUtilizationReport(effectiveFrom, effectiveTo, departmentId, leaveTypeId);
        byte[] csv =
                ReportCsvWriter.write(
                        new String[] {"Employee", "Leave Type", "Days Used", "Requests Count"},
                        rows.stream()
                                .map(
                                        row ->
                                                new String[] {
                                                    row.employeeName(),
                                                    row.leaveTypeName(),
                                                    row.daysUsed().toPlainString(),
                                                    String.valueOf(row.requestCount())
                                                })
                                .toList());
        return csvResponse(csv, "leave-utilization-" + effectiveFrom + "-to-" + effectiveTo + ".csv");
    }

    @GetMapping("/admin/reports/headcount")
    @PreAuthorize("hasRole('ADMIN')")
    String headcountPage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable EmploymentType employmentType,
            Model model) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(11).withDayOfMonth(1);
        List<HeadcountRow> rows =
                reports.headcountReport(effectiveFrom, effectiveTo, departmentId, employmentType);
        model.addAttribute("departmentOptions", org.listActiveDepartments());
        model.addAttribute("employmentTypeOptions", EmploymentType.values());
        model.addAttribute("rows", rows);
        model.addAttribute("chart", ReportCharts.headcount(rows));
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedEmploymentType", employmentType);
        model.addAttribute("selectedFrom", effectiveFrom);
        model.addAttribute("selectedTo", effectiveTo);
        return "admin/reports-headcount";
    }

    @GetMapping("/admin/reports/headcount.csv")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<byte[]> headcountCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID departmentId,
            @RequestParam(required = false) @Nullable EmploymentType employmentType) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(11).withDayOfMonth(1);
        List<HeadcountRow> rows =
                reports.headcountReport(effectiveFrom, effectiveTo, departmentId, employmentType);
        byte[] csv =
                ReportCsvWriter.write(
                        new String[] {"Month", "Department", "Active Count"},
                        rows.stream()
                                .map(
                                        row ->
                                                new String[] {
                                                    row.month().toString(),
                                                    row.departmentName() == null ? "" : row.departmentName(),
                                                    String.valueOf(row.activeCount())
                                                })
                                .toList());
        return csvResponse(csv, "headcount-" + effectiveFrom + "-to-" + effectiveTo + ".csv");
    }

    private void addOrgAndLeaveTypeOptions(Model model) {
        model.addAttribute("departmentOptions", org.listActiveDepartments());
        model.addAttribute("divisionOptions", org.listActiveDivisions());
        model.addAttribute("leaveTypeOptions", leaveTypes.listAll());
    }

    private LocalDate defaultYearStart() {
        return LocalDate.now(clock).withDayOfYear(1);
    }

    /**
     * Utilization counts a request the moment it's APPROVED, regardless of whether its dates
     * have elapsed (it reflects bookings for a period, not attendance already taken) — so the
     * default range should cover the whole current year, not stop at today, or an already-
     * approved trip booked for next month would silently disappear from the default view.
     */
    private LocalDate defaultYearEnd() {
        return LocalDate.now(clock).withMonth(12).withDayOfMonth(31);
    }

    private static ResponseEntity<byte[]> csvResponse(byte[] csv, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
                .body(csv);
    }

    /** RFC 5987 filename encoding — same convention as {@code FilesController#contentDisposition}. */
    private static String contentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
