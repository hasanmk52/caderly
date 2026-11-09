package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.audit.AuditAdminService.AuditRow;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeName;
import com.caderly.caderlyhr.tenant.TenantFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.json.JsonMapper;

class AuditLogViewTest {

    private static final UUID AISHA_USER = UUID.randomUUID();
    private static final UUID AISHA = UUID.randomUUID();
    private static final UUID PRIYA = UUID.randomUUID();
    private static final UUID LOGIN_ONLY_USER = UUID.randomUUID();
    private static final UUID BALANCE = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();

    private final AuditLogView view = new AuditLogView(formats(), messages(), JsonMapper.builder().build());

    private final AuditLogView.Lookups lookups = new AuditLogView.Lookups(
            Map.of(AISHA_USER, "aisha@northwind.test", LOGIN_ONLY_USER, "it@northwind.test"),
            List.of(new EmployeeName(AISHA, AISHA_USER, "Aisha Rahman"), new EmployeeName(PRIYA, null, "Priya Nair")),
            Map.of(BALANCE, "Vacation", REQUEST, "Vacation"));

    @Test
    void rows_balanceUpdate_namesTheActorTheOwnerAndTheChange() {
        var row = only(row(AISHA_USER, "ADMIN", "LeaveBalance", BALANCE, Action.UPDATE,
                "{\"employeeId\":\"%s\",\"year\":2026,\"granted\":28.00,\"updatedAt\":\"2026-09-01T10:00:00Z\"}".formatted(PRIYA),
                "{\"employeeId\":\"%s\",\"year\":2026,\"granted\":30.00,\"updatedAt\":\"2026-09-02T10:00:00Z\"}".formatted(PRIYA)));

        assertThat(row.actor()).isEqualTo("Aisha Rahman");
        assertThat(row.role()).isEqualTo("Admin");
        assertThat(row.kind()).isEqualTo("Leave balance");
        assertThat(row.label()).isEqualTo("Priya Nair · Vacation 2026");
        assertThat(row.change()).isEqualTo("granted 28 → 30");
    }

    @Test
    void rows_requestDecision_saysOnlyTheStatusChange_inUiWording() {
        String before = "{\"employeeId\":\"%s\",\"startDate\":\"2026-03-02\",\"endDate\":\"2026-03-06\",\"status\":\"PENDING\",\"decidedAt\":null}";
        String after = "{\"employeeId\":\"%s\",\"startDate\":\"2026-03-02\",\"endDate\":\"2026-03-06\",\"status\":\"REJECTED\",\"decidedAt\":\"2026-03-01T09:00:00Z\"}";
        var row = only(row(AISHA_USER, "MANAGER", "LeaveRequest", REQUEST, Action.UPDATE,
                before.formatted(PRIYA), after.formatted(PRIYA)));

        assertThat(row.label()).isEqualTo("Priya Nair · Vacation, Mon 2 – Fri 6 Mar 2026");
        assertThat(row.change()).isEqualTo("status Pending → Declined");
    }

    @Test
    void rows_severalFieldsChanged_showsTheFirstAndCountsTheRest() {
        var row = only(row(AISHA_USER, "ADMIN", "Employee", PRIYA, Action.UPDATE,
                "{\"firstName\":\"Pri\",\"lastName\":\"N\",\"jobTitle\":\"Engineer\"}",
                "{\"firstName\":\"Priya\",\"lastName\":\"Nair\",\"jobTitle\":\"Engineer\"}"));

        assertThat(row.label()).isEqualTo("Priya Nair");
        assertThat(row.change()).isEqualTo("first name Pri → Priya +1 more");
    }

    @Test
    void rows_createAndDelete_sayCreatedAndDeleted() {
        var rows = view.rows(List.of(
                row(AISHA_USER, "ADMIN", "Department", UUID.randomUUID(), Action.CREATE, null, "{\"name\":\"Finance\"}"),
                row(AISHA_USER, "ADMIN", "Department", UUID.randomUUID(), Action.DELETE, "{\"name\":\"Legal\"}", null)), lookups);

        assertThat(rows).extracting(AuditLogView.Row::label).containsExactly("Finance", "Legal");
        assertThat(rows).extracting(AuditLogView.Row::change).containsExactly("Created", "Deleted");
    }

    @Test
    void rows_onlyBookkeepingOrRedactedFieldsChanged_saysUpdated() {
        var row = only(row(LOGIN_ONLY_USER, "EMPLOYEE", "AppUser", LOGIN_ONLY_USER, Action.UPDATE,
                "{\"email\":\"it@northwind.test\",\"passwordHash\":\"[REDACTED]\",\"updatedAt\":\"2026-09-01T10:00:00Z\"}",
                "{\"email\":\"it@northwind.test\",\"passwordHash\":\"[REDACTED]\",\"updatedAt\":\"2026-09-02T10:00:00Z\"}"));

        assertThat(row.actor()).isEqualTo("it@northwind.test");
        assertThat(row.label()).isEqualTo("it@northwind.test");
        assertThat(row.change()).isEqualTo("Updated");
    }

    @Test
    void rows_withoutAPerson_isSystemOrSupport() {
        var rows = view.rows(List.of(
                row(null, "SYSTEM", "LeaveBalance", UUID.randomUUID(), Action.UPDATE, "{}", "{}"),
                row(UUID.randomUUID(), "SUPER_ADMIN", "Tenant", UUID.randomUUID(), Action.UPDATE, null, "{\"name\":\"Northwind\"}")), lookups);

        assertThat(rows).extracting(AuditLogView.Row::actor).containsExactly("System", "Caderly support");
        assertThat(rows).extracting(AuditLogView.Row::role).containsExactly(null, null);
        assertThat(rows.get(1).label()).isEqualTo("Northwind");
    }

    @Test
    void rows_updateRecordedWithOnlyTheNewValues_showsThem() {
        var row = only(row(UUID.randomUUID(), "SUPER_ADMIN", "Tenant", UUID.randomUUID(), Action.UPDATE, null, "{\"suspended\":true}"));

        assertThat(row.kind()).isEqualTo("Workspace");
        assertThat(row.change()).isEqualTo("suspended → Yes");
    }

    @Test
    void rows_malformedJsonOrUnknownKind_stillRenders() {
        var row = only(row(AISHA_USER, "ADMIN", "WidgetThing", UUID.randomUUID(), Action.UPDATE, "{not json", "{not json"));

        assertThat(row.kind()).isEqualTo("Widget thing");
        assertThat(row.label()).isEmpty();
        assertThat(row.change()).isEqualTo("Updated");
    }

    @Test
    void leaveRecordIds_areTheBalanceAndRequestRowsOnly() {
        var rows = List.of(
                row(null, "SYSTEM", "LeaveBalance", BALANCE, Action.UPDATE, null, null),
                row(null, "SYSTEM", "LeaveRequest", REQUEST, Action.UPDATE, null, null),
                row(null, "SYSTEM", "Employee", PRIYA, Action.UPDATE, null, null));

        assertThat(AuditLogView.leaveRecordIds(rows)).containsExactlyInAnyOrder(BALANCE, REQUEST);
    }

    private AuditLogView.Row only(AuditRow row) {
        return view.rows(List.of(row), lookups).getFirst();
    }

    private static AuditRow row(UUID actor, String role, String type, UUID entityId, Action action, String before, String after) {
        return new AuditRow(UUID.randomUUID(), ZonedDateTime.now(ZoneOffset.UTC), actor, role, type,
                entityId.toString(), action, before, after);
    }

    private static DisplayFormats formats() {
        TenantFacade tenants = mock(TenantFacade.class);
        when(tenants.currentTimezone()).thenReturn(ZoneOffset.UTC);
        return new DisplayFormats(tenants, messages(), Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private static ResourceBundleMessageSource messages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
}
