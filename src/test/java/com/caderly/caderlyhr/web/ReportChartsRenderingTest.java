package com.caderly.caderlyhr.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caderly.caderlyhr.identity.Role;
import com.caderly.caderlyhr.people.Employee;
import com.caderly.caderlyhr.support.RbacTestSupport;
import com.caderly.caderlyhr.tenant.Tenant;
import com.caderly.caderlyhr.tenant.TenantContext;
import com.caderly.caderlyhr.timeoff.LeaveRequest;
import com.caderly.caderlyhr.timeoff.LeaveRequestService;
import com.caderly.caderlyhr.timeoff.LeaveTypeService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PRD FR-10.2/10.3: the report pages render their charts above the tables, and only with data. */
class ReportChartsRenderingTest extends RbacTestSupport {

    @Autowired private LeaveTypeService leaveTypeService;
    @Autowired private LeaveRequestService leaveRequestService;

    @BeforeEach
    void seedTenant() {
        slug = "charts" + UUID.randomUUID().toString().substring(0, 8);
        tenantId = TenantContext.runAsSystem("test: seed tenant", () -> tenants.save(new Tenant(slug, "Charts Co")).getId());
    }

    @Test
    void leaveUtilization_withApprovedLeave_rendersTheStackedBarChart() throws Exception {
        UUID vacation = run(() -> leaveTypeService.create(
                "Vacation", null, null, true, true, false, true, new BigDecimal("30"), null).requireId());
        Employee admin = createEmployee("Aisha", "Rahman", LocalDate.now().minusYears(1), null, null);
        grantRole(admin, Role.ADMIN);
        Employee priya = createEmployee("Priya", "Nair", LocalDate.now().minusYears(1), null, admin.requireId());
        // Next week's Monday and Tuesday, kept inside the current year for the default report range.
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (monday.plusDays(1).getYear() != LocalDate.now().getYear()) {
            monday = LocalDate.now().withDayOfYear(1).plusYears(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
        }
        LocalDate start = monday;
        run(() -> {
            LeaveRequest request = leaveRequestService.book(priya.requireId(), vacation, start, start.plusDays(1), false, false, null, BASE_URL);
            leaveRequestService.approve(request.requireId(), admin.userId(), admin.requireId(), admin.fullName(), true, null);
        });

        mockMvc
                .perform(url("/admin/reports/leave-utilization?from=" + start.withDayOfYear(1) + "&to="
                                + start.withDayOfYear(start.lengthOfYear()))
                        .with(user("admin@charts.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"chart-seg chart-s1\"")))
                .andExpect(content().string(containsString("data-chart-tip=\"No department · Vacation · 2 days\"")))
                .andExpect(content().string(containsString("</html>")));
    }

    @Test
    void headcount_withActiveEmployees_rendersTheLineChart() throws Exception {
        createEmployee("Karim", "Saleh", LocalDate.now().minusYears(2), null, null);

        mockMvc
                .perform(url("/admin/reports/headcount").with(user("admin@charts.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"chart-path\"")))
                .andExpect(content().string(containsString("Line chart of active employees per month")))
                .andExpect(content().string(containsString("</html>")));
    }

    @Test
    void leaveUtilization_withNoData_showsTheEmptyStateAndNoChart() throws Exception {
        mockMvc
                .perform(url("/admin/reports/leave-utilization").with(user("admin@charts.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("caderly-chart"))))
                .andExpect(content().string(containsString("No approved leave matches these filters.")));
    }

    private MockHttpServletRequestBuilder url(String pathAndQuery) {
        return get(URI.create("http://" + slug + ".localhost" + pathAndQuery));
    }
}
