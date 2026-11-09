package com.caderly.caderlyhr.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.org.Division;
import com.caderly.caderlyhr.org.DivisionRepository;
import com.caderly.caderlyhr.tenantisolation.TenantIsolationTestBase;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code audit_entry} is system-scoped infrastructure with no RLS/{@code @TenantId} (ADR 0005
 * decision B), so — exactly like {@code NotificationAdminServiceTest} for {@code email_outbox} —
 * the isolation this file asserts is enforced nowhere else: delete the {@code tenantId} predicate
 * in {@link AuditAdminService} and nothing underneath it objects.
 */
class AuditAdminServiceTest extends TenantIsolationTestBase {

    @Autowired private AuditAdminService audits;
    @Autowired private DivisionRepository divisions;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void list_showsOnlyTheCurrentTenantsWrites() {
        UUID divisionIdA = createDivisionIn(tenantA, "Engineering A");
        createDivisionIn(tenantB, "Engineering B");

        assertThat(asTenant(tenantA, () -> audits.list(null, null, null, null, null, false, 0)).getContent())
                .extracting(AuditAdminService.AuditRow::entityId)
                .containsExactly(divisionIdA.toString());
    }

    @Test
    void list_whenFilteredByEntityType_returnsOnlyThatType() {
        createDivisionIn(tenantA, "Filtered");

        assertThat(
                        asTenant(tenantA, () -> audits.list("Division", null, null, null, null, false, 0))
                                .getContent())
                .isNotEmpty();
        assertThat(
                        asTenant(tenantA, () -> audits.list("Employee", null, null, null, null, false, 0))
                                .getContent())
                .isEmpty();
    }

    @Test
    void findOne_onAnotherTenantsRow_reportsNotFoundRatherThanForbidden() {
        createDivisionIn(tenantB, "Belongs To B");
        UUID rowId =
                asTenant(tenantB, () -> audits.list(null, null, null, null, null, false, 0))
                        .getContent()
                        .get(0)
                        .id();

        assertThatThrownBy(() -> asTenant(tenantA, () -> audits.findOne(rowId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findOne_prettyPrintsTheStoredJson() {
        UUID divisionId = createDivisionIn(tenantA, "Pretty");

        UUID rowId =
                asTenant(tenantA, () -> audits.list("Division", null, null, null, null, false, 0))
                        .getContent()
                        .stream()
                        .filter(row -> divisionId.toString().equals(row.entityId()))
                        .findFirst()
                        .orElseThrow()
                        .id();

        AuditAdminService.AuditDiff diff = asTenant(tenantA, () -> audits.findOne(rowId));
        assertThat(diff.afterJson()).contains("\n");
    }

    @Test
    void list_hidesSignInBookkeeping_unlessAskedFor() {
        String signIn = insertAppUserUpdate(tenantA, "2026-09-18T10:32:19Z", "2026-09-19T08:00:00Z", null, null);
        String lockout = insertAppUserUpdate(tenantA, null, null, null, "2026-09-19T08:15:00Z");

        assertThat(asTenant(tenantA, () -> audits.list(null, null, null, null, null, false, 0)).getContent())
                .extracting(AuditAdminService.AuditRow::entityId)
                .containsExactly(lockout);
        assertThat(asTenant(tenantA, () -> audits.list(null, null, null, null, null, true, 0)).getContent())
                .extracting(AuditAdminService.AuditRow::entityId)
                .containsExactlyInAnyOrder(signIn, lockout);
    }

    /** An AppUser update row as a sign-in or a lockout writes it: full snapshots on both sides. */
    private String insertAppUserUpdate(
            UUID tenantId, String lastLoginBefore, String lastLoginAfter, String lockedBefore, String lockedAfter) {
        String userId = UUID.randomUUID().toString();
        String snapshot =
                "{\"id\": \"%s\", \"email\": \"a@b.test\", \"status\": \"ACTIVE\", \"passwordHash\": \"[REDACTED]\","
                        + " \"updatedAt\": %s, \"lastLoginAt\": %s, \"lockedUntil\": %s}";
        jdbc.update(
                """
                INSERT INTO caderly_hr.audit_entry
                  (id, tenant_id, actor_role, entity_type, entity_id, action, before_json, after_json)
                VALUES (?, ?, 'SYSTEM', 'AppUser', ?, 'UPDATE', ?::jsonb, ?::jsonb)
                """,
                UUID.randomUUID(),
                tenantId,
                userId,
                snapshot.formatted(userId, json(lastLoginBefore), json(lastLoginBefore), json(lockedBefore)),
                snapshot.formatted(userId, json(lastLoginAfter), json(lastLoginAfter), json(lockedAfter)));
        return userId;
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private UUID createDivisionIn(UUID tenantId, String name) {
        return asTenant(
                tenantId,
                () -> transactions.execute(status -> divisions.save(Division.create(name, null)).getId()));
    }
}
