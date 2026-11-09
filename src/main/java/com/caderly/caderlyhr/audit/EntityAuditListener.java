package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.common.AuditActor;
import com.caderly.caderlyhr.common.ClientIpResolver;
import com.caderly.caderlyhr.common.MdcKeys;
import com.caderly.caderlyhr.common.TenantAwareEntity;
import com.caderly.caderlyhr.tenant.TenantContext;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreRemove;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code @EntityListeners(EntityAuditListener.class)} on every audited entity (17 of the 18 {@code
 * TenantAwareEntity} subclasses — {@code identity.PasswordResetToken} is the one exclusion; ADR
 * 0017 records why) writes an {@code audit_entry} row for every create, update, and delete
 * (FR-11.1).
 *
 * <p><strong>Why a raw JDBC insert, not {@code entityManager.persist(...)}.</strong> {@code
 * @PostPersist}/{@code @PostUpdate}/{@code @PreRemove} all fire <em>during</em> Hibernate's flush.
 * Calling back into the same {@code EntityManager} to persist a different entity from inside that
 * callback is documented Hibernate territory to avoid — the flush's action queue for this session
 * has already been built, and inserting into it mid-flush is unsupported, not merely
 * discouraged. A plain {@link JdbcTemplate} insert instead runs on the same transaction-bound JDBC
 * {@code Connection} (Spring binds it by {@code DataSource}, and this bean's {@code JdbcTemplate}
 * uses the same one JPA does) without touching the Hibernate session at all — it commits or rolls
 * back with the surrounding transaction exactly like a normal persist would (CLAUDE.md §6a: this
 * is a synchronous in-transaction write, never an outbox), just without going through Hibernate.
 *
 * <p><strong>Why {@code @PostLoad} + {@code @PostUpdate} diffing, not the entity's current state
 * alone.</strong> By the time any JPA callback fires, Hibernate has already merged new field
 * values into the Java object — there is no framework-native way to see what a field held
 * <em>before</em> the change. Every service in this codebase follows a load-mutate-flush pattern
 * (private setters, intent methods — CLAUDE.md §10), so a snapshot taken on load and diffed at
 * flush time is reliable: {@link #onLoad} stashes the entity's current fields in {@link
 * TenantAwareEntity#auditSnapshot()}, and {@link #onUpdate} reads that stash as "before" and the
 * entity's current fields as "after", then refreshes the stash so a second mutation later in the
 * same transaction diffs correctly against the first mutation's result rather than the original
 * load.
 *
 * <p>Spring-managed ({@code @Component}) rather than instantiated by the JPA provider by
 * reflection: Spring Boot wires Hibernate's {@code hibernate.resource.beans.container} to resolve
 * {@code @EntityListeners} classes as Spring beans automatically, which is what lets this class
 * take constructor-injected dependencies instead of reaching for a static {@code
 * ApplicationContext} holder.
 */
@Component
public class EntityAuditListener {

    private final JdbcTemplate jdbc;
    private final AuditFieldSnapshotter snapshotter;
    private final Clock clock;

    EntityAuditListener(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.snapshotter = new AuditFieldSnapshotter(mapper);
        this.clock = clock;
    }

    @PostLoad
    public void onLoad(TenantAwareEntity entity) {
        entity.setAuditSnapshot(snapshotter.snapshot(entity));
    }

    @PostPersist
    public void onInsert(TenantAwareEntity entity) {
        String after = snapshotter.snapshot(entity);
        write(entity, null, after, Action.CREATE);
        entity.setAuditSnapshot(after);
    }

    /**
     * {@code @PostUpdate}, not {@code @PreUpdate}: Hibernate runs {@code @PreUpdate} each time a
     * flush dirty-checks the entity, including an auto-flush before a query on another table that
     * then writes nothing, so one real UPDATE could log twice. {@code @PostUpdate} runs once per
     * UPDATE actually executed.
     */
    @PostUpdate
    public void onUpdate(TenantAwareEntity entity) {
        String before = entity.auditSnapshot();
        String after = snapshotter.snapshot(entity);
        write(entity, before, after, Action.UPDATE);
        entity.setAuditSnapshot(after);
    }

    @PreRemove
    public void onRemove(TenantAwareEntity entity) {
        write(entity, snapshotter.snapshot(entity), null, Action.DELETE);
    }

    /**
     * Records a fact that no entity lifecycle produced — currently only the start and end of a
     * Super Admin impersonation session (PRD FR-1.8).
     *
     * <p>Public, and the only public write on this class, because {@code audit.system.AuditEntry}
     * has no persist-capable constructor by design (see its Javadoc): the raw insert below is the
     * single way a row is ever written, and an event with no entity behind it still has to go
     * through it rather than grow a second path. The actor is passed in rather than read from the
     * {@code SecurityContext} for the same reason the impersonation events exist at all — the
     * actor is the <em>Super Admin</em>, who is not the authenticated principal on the tenant
     * subdomain where these are recorded.
     *
     * <p>Runs on whatever transaction the caller already has, or on its own connection if none —
     * {@code audit_entry} carries no RLS (ADR 0005 decision B), so it needs no {@code
     * app.tenant_id} to have been set.
     */
    public void recordManualEvent(
            @Nullable UUID tenantId,
            UUID actorUserId,
            String actorRole,
            String entityType,
            @Nullable String entityId,
            Action action,
            @Nullable String afterJson) {
        insert(tenantId, actorUserId, actorRole, entityType, entityId, action, null, afterJson);
    }

    private void write(
            TenantAwareEntity entity, @Nullable String before, @Nullable String after, Action action) {
        UUID entityId = entity.getId();

        UUID actorUserId = null;
        String actorRole = "SYSTEM";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuditActor actor) {
            actorUserId = actor.actorId();
            actorRole = highestRole(actor.roleNames());
        }

        insert(
                TenantContext.get().orElse(null),
                actorUserId,
                actorRole,
                entity.getClass().getSimpleName(),
                entityId == null ? null : entityId.toString(),
                action,
                before,
                after);
    }

    /**
     * The one place an {@code audit_entry} row is created. Both the JPA-callback path and {@link
     * #recordManualEvent} funnel through here so the statement text, the primary-key strategy, and
     * the request-context columns cannot drift apart between them.
     */
    private void insert(
            @Nullable UUID tenantId,
            @Nullable UUID actorUserId,
            String actorRole,
            String entityType,
            @Nullable String entityId,
            Action action,
            @Nullable String before,
            @Nullable String after) {
        Timestamp now = Timestamp.from(clock.instant());

        String ip = null;
        String userAgent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttrs) {
            var request = servletAttrs.getRequest();
            ip = ClientIpResolver.resolve(request);
            userAgent = request.getHeader("User-Agent");
            // Client-controlled; audit_entry.user_agent is varchar(500) and an over-length value
            // would roll back the business write that triggered the audit (e.g. an account lock).
            if (userAgent != null && userAgent.length() > 500) {
                userAgent = userAgent.substring(0, 500);
            }
        }
        String requestId = MDC.get(MdcKeys.REQUEST_ID);

        // Schema-qualified: raw JDBC, unlike Hibernate-generated SQL, is not rewritten by
        // hibernate.default_schema (same reason people.EmployeeRepository's native recursive-CTE
        // query hardcodes caderly_hr. — see that method's Javadoc).
        //
        // PK generated the same way BaseEntity's @UuidGenerator(style = VERSION_7) does for every
        // other entity (time-ordered, PRD §21) — this insert bypasses Hibernate's id generation
        // entirely, so it calls the same strategy Hibernate itself delegates VERSION_7 to, rather
        // than a random UUIDv4 that would fragment the audit_entry primary key index on a
        // high-volume, append-only table. The `session` argument is unused by this strategy.
        jdbc.update(
                """
                INSERT INTO caderly_hr.audit_entry
                  (id, tenant_id, actor_user_id, actor_role, occurred_at, entity_type, entity_id,
                   action, before_json, after_json, ip, user_agent, request_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                """,
                UuidVersion7Strategy.INSTANCE.generateUuid(null),
                tenantId,
                actorUserId,
                actorRole,
                now,
                entityType,
                entityId,
                action.name(),
                before,
                after,
                ip,
                userAgent,
                requestId,
                now,
                now);
    }

    /** Highest-privilege role wins when a user holds more than one (PRD §26's hierarchy). */
    private static String highestRole(java.util.Set<String> roleNames) {
        // Checked first, and not part of the hierarchy: an impersonated principal reports this
        // marker *instead of* the Admin's real roles, so every write made during a Super Admin
        // support session is distinguishable from one the Admin made themselves (PRD FR-1.8).
        if (roleNames.contains(AuditActor.IMPERSONATION_ROLE)) {
            return AuditActor.IMPERSONATION_ROLE;
        }
        if (roleNames.contains("ADMIN")) {
            return "ADMIN";
        }
        if (roleNames.contains("MANAGER")) {
            return "MANAGER";
        }
        if (roleNames.contains("EMPLOYEE")) {
            return "EMPLOYEE";
        }
        return "UNKNOWN";
    }
}
