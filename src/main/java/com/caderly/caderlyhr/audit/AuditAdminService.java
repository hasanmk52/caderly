package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.AuditEntry;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.common.NotFoundException;
import com.caderly.caderlyhr.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Backs the Admin write-audit viewer at {@code /admin/audit-log} (PRD §18.3, FR-11.3).
 *
 * <p><strong>This class is the tenant boundary for {@code audit_entry}.</strong> That table is
 * system-scoped infrastructure — no {@code @TenantId}, no RLS (ADR 0005 decision B, ADR 0017) —
 * which is exactly what lets {@link EntityAuditListener} write a row with no resolved tenant filter to
 * satisfy (e.g. a scheduled job's write). The flip side is that nothing underneath here stops a
 * query from returning another tenant's rows: the tenant argument below, always read from {@link
 * TenantContext} rather than a caller-supplied parameter, is the only thing that does.
 */
@Service
public class AuditAdminService {

    public static final int PAGE_SIZE = 25;

    private final AuditEntryRepository entries;
    private final ObjectMapper mapper;
    private final com.caderly.caderlyhr.tenant.TenantFacade tenants;

    AuditAdminService(
            AuditEntryRepository entries,
            ObjectMapper mapper,
            com.caderly.caderlyhr.tenant.TenantFacade tenants) {
        this.entries = entries;
        this.mapper = mapper;
        this.tenants = tenants;
    }

    /**
     * One page of this tenant's write audit, newest first.
     *
     * <p>{@code tenants.currentTimezone()} is read here, not cached at construction — this bean is
     * a singleton built once at startup, outside any resolved tenant, so caching it in the
     * constructor would fail immediately (same reason {@code NotificationAdminService} reads it
     * per call rather than once).
     */
    @Transactional(readOnly = true)
    public Page<AuditRow> list(
            @Nullable String entityType,
            @Nullable Action action,
            @Nullable UUID actorUserId,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            boolean includeSignIns,
            int page) {
        UUID tenantId = TenantContext.require();
        ZoneId zone = tenants.currentTimezone();
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        return entries
                .findAll(
                        forTenant(tenantId)
                                .and(withEntityType(entityType))
                                .and(withAction(action))
                                .and(withActor(actorUserId))
                                .and(occurredBetween(fromInstant, toInstant))
                                .and(notSignInBookkeeping(includeSignIns)),
                        PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "occurredAt")))
                .map(row -> AuditRow.of(row, zone));
    }

    /**
     * The "view diff" modal's content. A row belonging to another tenant is reported as <em>not
     * found</em>, not forbidden, matching {@code NotificationAdminService.requeue}'s reasoning: it
     * confirms nothing about whether the id exists.
     */
    @Transactional(readOnly = true)
    public AuditDiff findOne(UUID id) {
        UUID tenantId = TenantContext.require();
        AuditEntry row =
                entries
                        .findById(id)
                        .filter(candidate -> tenantId.equals(candidate.tenantId()))
                        .orElseThrow(() -> new NotFoundException("AUDIT_ENTRY_NOT_FOUND", "Audit entry not found"));
        return new AuditDiff(row.entityType(), row.action(), prettyPrint(row.beforeJson()), prettyPrint(row.afterJson()));
    }

    /** Best-effort: a row without this formatting still renders, just unindented. */
    private @Nullable String prettyPrint(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        } catch (JacksonException malformed) {
            return json;
        }
    }

    public record AuditDiff(
            String entityType, Action action, @Nullable String beforeJson, @Nullable String afterJson) {}

    /** The isolation boundary. Never optional, never taken from the caller. */
    private static Specification<AuditEntry> forTenant(UUID tenantId) {
        return (root, query, builder) -> builder.equal(root.get("tenantId"), tenantId);
    }

    private static Specification<AuditEntry> withEntityType(@Nullable String entityType) {
        return (root, query, builder) ->
                entityType == null || entityType.isBlank()
                        ? builder.conjunction()
                        : builder.equal(root.get("entityType"), entityType);
    }

    private static Specification<AuditEntry> withAction(@Nullable Action action) {
        return (root, query, builder) ->
                action == null ? builder.conjunction() : builder.equal(root.get("action"), action);
    }

    private static Specification<AuditEntry> withActor(@Nullable UUID actorUserId) {
        return (root, query, builder) ->
                actorUserId == null ? builder.conjunction() : builder.equal(root.get("actorUserId"), actorUserId);
    }

    /**
     * A sign-in stamps {@code AppUser.lastLoginAt}, which the listener records as an update like any
     * other. Those rows repeat the Login attempts tab, so the Writes tab drops an AppUser update
     * whose snapshots are equal once that stamp is removed. Done in SQL, not after paging, so every
     * page stays full; {@code jsonb_delete} is the function behind Postgres's {@code jsonb - text}.
     * A lockout changes {@code lockedUntil} and therefore always stays visible.
     */
    private static Specification<AuditEntry> notSignInBookkeeping(boolean includeSignIns) {
        return (root, query, builder) -> {
            if (includeSignIns) {
                return builder.conjunction();
            }
            var before = builder.function("jsonb_delete", String.class, root.get("beforeJson"),
                    builder.literal("lastLoginAt"), builder.literal("updatedAt"));
            var after = builder.function("jsonb_delete", String.class, root.get("afterJson"),
                    builder.literal("lastLoginAt"), builder.literal("updatedAt"));
            return builder.not(builder.and(
                    builder.equal(root.get("entityType"), "AppUser"),
                    builder.equal(root.get("action"), Action.UPDATE),
                    builder.equal(before, after)));
        };
    }

    private static Specification<AuditEntry> occurredBetween(
            @Nullable Instant from, @Nullable Instant to) {
        return (root, query, builder) -> {
            Predicate predicate = builder.conjunction();
            if (from != null) {
                predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicate = builder.and(predicate, builder.lessThan(root.get("occurredAt"), to));
            }
            return predicate;
        };
    }

    /**
     * What the table renders. {@code actorUserId} is shown as-is rather than resolved to an
     * email: doing that would need {@code audit} to read {@code identity}, and {@code identity}
     * already reads {@code audit} (the lockout re-key's failure count, ADR 0017) — resolving both
     * directions would be a package cycle. An Admin can still trace the exact account from the id.
     */
    public record AuditRow(
            UUID id,
            ZonedDateTime occurredAt,
            @Nullable UUID actorUserId,
            @Nullable String actorRole,
            String entityType,
            @Nullable String entityId,
            Action action,
            @Nullable String beforeJson,
            @Nullable String afterJson) {

        static AuditRow of(AuditEntry row, ZoneId zone) {
            return new AuditRow(
                    row.requireId(),
                    java.util.Objects.requireNonNull(row.occurredAt()).atZone(zone),
                    row.actorUserId(),
                    row.actorRole(),
                    row.entityType(),
                    row.entityId(),
                    row.action(),
                    row.beforeJson(),
                    row.afterJson());
        }

        public boolean hasDiff() {
            return beforeJson != null || afterJson != null;
        }
    }
}
