package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.calendar.CalendarService;
import com.caderly.caderlyhr.calendar.CalendarService.EmployeeCalendarRow;
import com.caderly.caderlyhr.calendar.CalendarService.TeamCalendarView;
import com.caderly.caderlyhr.documents.CompanyFile;
import com.caderly.caderlyhr.documents.CompanyFileService;
import com.caderly.caderlyhr.identity.AppUserPrincipal;
import com.caderly.caderlyhr.identity.DashboardLayoutService;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.people.EmployeeService;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeePeerInfo;
import com.caderly.caderlyhr.timeoff.BalanceService;
import com.caderly.caderlyhr.timeoff.LeaveBalance;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.ApprovedLeaveEntry;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.HolidayMarker;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Home dashboard (PRD §24.2, UI Guidelines §8.2, ADR 0015): the shell renders the
 * greeting only, and each widget is its own {@code hx-get} fragment endpoint loaded independently
 * on page load (parallelizes, per UI Guidelines §8.2) rather than one method assembling all six
 * widgets' data up front. The six widgets are Book Time Off, My Peers, Time Off Today, My Days
 * Off, Upcoming Holidays, and Resources — Company News is deliberately not one of them (see ADR
 * 0015: its MVP form is a zero-data static tile, and the real thing is Phase 2).
 */
@Controller
class HomeController {

    private final EmployeeService employees;
    private final BalanceService balances;
    private final PeopleFacade people;
    private final TimeoffFacade timeoff;
    private final CalendarService calendarService;
    private final CompanyFileService companyFiles;
    private final DashboardLayoutService dashboardLayouts;
    private final Clock clock;

    HomeController(
            EmployeeService employees,
            BalanceService balances,
            PeopleFacade people,
            TimeoffFacade timeoff,
            CalendarService calendarService,
            CompanyFileService companyFiles,
            DashboardLayoutService dashboardLayouts,
            Clock clock) {
        this.employees = employees;
        this.balances = balances;
        this.people = people;
        this.timeoff = timeoff;
        this.calendarService = calendarService;
        this.companyFiles = companyFiles;
        this.dashboardLayouts = dashboardLayouts;
        this.clock = clock;
    }

    /**
     * Any signed-in user of this tenant; the role hierarchy makes EMPLOYEE the floor.
     *
     * <p>{@code firstName} is null for a principal with no linked {@link Employee} — an Admin-only
     * account such as {@code DevDataSeeder}'s dev bootstrap, or a test's mock {@code
     * UserDetails} that isn't really an {@link AppUserPrincipal} at all — so the greeting (PRD
     * §24.2) falls back to a name-free "Welcome!" instead of failing.
     */
    @GetMapping("/")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String home(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("firstName", resolveEmployee(principal).map(Employee::firstName).orElse(null));
        model.addAttribute("slots", principal == null
                        ? DashboardLayoutService.defaultLayout()
                        : dashboardLayouts.layoutFor(principal.userId()));
        return "home";
    }

    /**
     * Saves the caller's own layout. The user comes from the principal only, never a request
     * parameter. Replies 200 with {@code HX-Redirect} rather than a Spring redirect: htmx cannot
     * observe a header on a redirect its XHR already followed (CURRENT_PHASE, ADR 0019 decision H).
     */
    @PostMapping("/dashboard/layout")
    @PreAuthorize("hasRole('EMPLOYEE')")
    ResponseEntity<Void> saveLayout(
            @Nullable @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(defaultValue = "") List<String> order,
            @RequestParam(defaultValue = "") List<String> hidden) {
        if (principal != null) {
            dashboardLayouts.save(principal.userId(), order, hidden);
        }
        return ResponseEntity.ok().header("HX-Redirect", "/").build();
    }

    @PostMapping("/dashboard/layout/reset")
    @PreAuthorize("hasRole('EMPLOYEE')")
    ResponseEntity<Void> resetLayout(@Nullable @AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal != null) {
            dashboardLayouts.reset(principal.userId());
        }
        return ResponseEntity.ok().header("HX-Redirect", "/").build();
    }

    @GetMapping("/widgets/book-time-off")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String bookTimeOffWidget(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        var employee = resolveEmployee(principal);
        model.addAttribute("hasEmployee", employee.isPresent());
        model.addAttribute("leaveBalances", employee.map(e -> balanceCards(e.requireId())).orElse(List.of()));
        return "home/widgets :: bookTimeOff";
    }

    @GetMapping("/widgets/my-peers")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String myPeersWidget(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Optional<Employee> employee = resolveEmployee(principal);
        List<EmployeePeerInfo> peers = employee.map(e -> people.listPeers(e.requireId())).orElse(List.of());
        Set<UUID> outTodayIds =
                employee
                        .map(
                                e ->
                                        timeoff
                                                .listApprovedLeaveInRange(
                                                        LocalDate.now(clock),
                                                        LocalDate.now(clock),
                                                        peers.stream().map(EmployeePeerInfo::employeeId).toList(),
                                                        null)
                                                .stream()
                                                .map(ApprovedLeaveEntry::employeeId)
                                                .collect(Collectors.toSet()))
                        .orElse(Set.of());
        List<PeerRow> peerRows =
                peers.stream().map(p -> toPeerRow(p, outTodayIds.contains(p.employeeId()))).toList();
        model.addAttribute("peers", peerRows);
        model.addAttribute("peersOutToday", peerRows.stream().filter(PeerRow::outToday).toList());
        return "home/widgets :: myPeers";
    }

    /** Donut ring circumference for r=34 (2 * pi * r) — see {@code home/widgets.html}'s SVG. */
    private static final double RING_CIRCUMFERENCE = 213.63;

    @GetMapping("/widgets/time-off-today")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String timeOffTodayWidget(Model model) {
        LocalDate today = LocalDate.now(clock);
        TeamCalendarView view = calendarService.buildTeamCalendar(today, today, null, null, null);
        List<EmployeeCalendarRow> outToday =
                view.rows().stream().filter(row -> !row.bars().isEmpty()).toList();
        int total = view.rows().size();
        model.addAttribute("totalEmployees", total);
        model.addAttribute(
                "outToday", outToday.stream().map(r -> new OutTodayRow(r.fullName(), r.departmentName())).toList());
        double ringDash = total == 0 ? 0 : RING_CIRCUMFERENCE * outToday.size() / total;
        model.addAttribute("ringDasharray", "%.2f %.2f".formatted(ringDash, RING_CIRCUMFERENCE));
        return "home/widgets :: timeOffToday";
    }

    @GetMapping("/widgets/my-days-off")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String myDaysOffWidget(@Nullable @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        LocalDate today = LocalDate.now(clock);
        Optional<Employee> employee = resolveEmployee(principal);
        List<ApprovedLeaveEntry> upcoming =
                employee
                        .map(
                                e ->
                                        timeoff.listAllApprovedLeaveForEmployee(e.requireId()).stream()
                                                .filter(entry -> !entry.endDate().isBefore(today))
                                                .sorted(Comparator.comparing(ApprovedLeaveEntry::startDate))
                                                .limit(5)
                                                .toList())
                        .orElse(List.of());
        model.addAttribute("myDaysOff", upcoming);
        model.addAttribute("ownEmployeeId", employee.map(Employee::requireId).orElse(null));
        return "home/widgets :: myDaysOff";
    }

    @GetMapping("/widgets/upcoming-holidays")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String upcomingHolidaysWidget(Model model) {
        LocalDate today = LocalDate.now(clock);
        model.addAttribute(
                "upcomingHolidays",
                groupConsecutive(timeoff.listPublicHolidaysInRange(today, today.plusMonths(6))).stream()
                        .limit(5)
                        .toList());
        return "home/widgets :: upcomingHolidays";
    }

    @GetMapping("/widgets/resources")
    @PreAuthorize("hasRole('EMPLOYEE')")
    String resourcesWidget(Model model) {
        List<CompanyFile> files = companyFiles.listAll().stream().limit(3).toList();
        model.addAttribute("resourceFiles", files);
        return "home/widgets :: resources";
    }

    private Optional<Employee> resolveEmployee(@Nullable AppUserPrincipal principal) {
        return principal == null ? Optional.empty() : employees.findByUserId(principal.userId());
    }

    private List<BalanceCard> balanceCards(UUID employeeId) {
        return balances.listCurrentYearForEmployee(employeeId).stream()
                .map(
                        (LeaveBalance b) ->
                                new BalanceCard(
                                        b.leaveType().requireId(),
                                        b.leaveType().name(),
                                        b.leaveType().icon(),
                                        b.leaveType().color(),
                                        b.granted(),
                                        b.used(),
                                        b.remaining()))
                .toList();
    }

    private static PeerRow toPeerRow(EmployeePeerInfo peer, boolean outToday) {
        return new PeerRow(
                peer.employeeId(),
                peer.firstName(),
                peer.lastName(),
                peer.fullName(),
                peer.departmentName(),
                peer.jobTitle(),
                outToday);
    }

    record BalanceCard(
            UUID leaveTypeId,
            String leaveTypeName,
            @Nullable String icon,
            @Nullable String color,
            BigDecimal granted,
            BigDecimal used,
            BigDecimal remaining) {}

    record PeerRow(
            UUID employeeId,
            String firstName,
            String lastName,
            String fullName,
            @Nullable String departmentName,
            @Nullable String jobTitle,
            boolean outToday) {}

    record OutTodayRow(String fullName, @Nullable String departmentName) {}

    /**
     * A holiday entered day by day ("UAE National Day" on 2 and 3 Dec) reads as one row with a date
     * range. Markers arrive in date order; a run continues while the name repeats on the next day.
     */
    static List<HolidayGroup> groupConsecutive(List<HolidayMarker> markers) {
        List<HolidayGroup> groups = new java.util.ArrayList<>();
        for (HolidayMarker marker : markers) {
            HolidayGroup last = groups.isEmpty() ? null : groups.getLast();
            if (last != null && last.name().equals(marker.name()) && last.end().plusDays(1).equals(marker.date())) {
                groups.set(groups.size() - 1, new HolidayGroup(last.name(), last.start(), marker.date()));
            } else {
                groups.add(new HolidayGroup(marker.name(), marker.date(), marker.date()));
            }
        }
        return groups;
    }

    record HolidayGroup(String name, LocalDate start, LocalDate end) {}
}
