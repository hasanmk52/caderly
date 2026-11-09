package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.audit.AuditAdminService.AuditRow;
import com.caderly.caderlyhr.audit.system.AuditEntry.Action;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeName;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns raw write-audit rows into what a person reads (PRD §18.3): who did it by name, which record
 * in words, and the change in one line. Names are resolved here in {@code web} because {@code audit}
 * cannot read {@code identity} or {@code people} without a package cycle (ADR 0017); the controller
 * fetches the {@link Lookups} once per page so labelling a row never queries.
 */
@Component
class AuditLogView {

    /** The types {@code EntityAuditListener} and the manual events write, for the filter select. */
    static final List<String> KINDS = List.of(
            "AppUser", "BankDetail", "Benefit", "CompanyFile", "DashboardLayout", "Department", "Division",
            "Education", "EmergencyContact", "Employee", "EmployeeDocument", "EmployeeManagerHistory",
            "EmployeeStatusHistory", "GovernmentId", "ImpersonationSession", "LeaveBalance", "LeaveRequest",
            "LeaveType", "PublicHoliday", "Tenant", "UserRole");

    /** Written on every update by the persistence layer itself, so never "what changed". */
    private static final Set<String> BOOKKEEPING = Set.of("id", "tenantId", "createdAt", "updatedAt", "version");

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ENUM_VALUE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern CAMEL_HUMP = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final int MAX_VALUE_LENGTH = 40;

    private final DisplayFormats fmt;
    private final MessageSource messages;
    private final ObjectMapper mapper;

    AuditLogView(DisplayFormats fmt, MessageSource messages, ObjectMapper mapper) {
        this.fmt = fmt;
        this.messages = messages;
        this.mapper = mapper;
    }

    record Lookups(Map<UUID, String> userEmails, List<EmployeeName> employees, Map<UUID, String> leaveTypes) {}

    /** {@code role} is null when the actor is not a tenant user (the system, Caderly support). */
    record Row(
            UUID id,
            ZonedDateTime occurredAt,
            String actor,
            @Nullable String role,
            String kind,
            String label,
            String change,
            boolean hasDiff) {}

    record Option(String value, String label) {}

    /** The page's balance and request ids, whose leave type the snapshots leave out. */
    static Set<UUID> leaveRecordIds(List<AuditRow> rows) {
        Set<UUID> ids = new java.util.HashSet<>();
        for (AuditRow row : rows) {
            if (("LeaveBalance".equals(row.entityType()) || "LeaveRequest".equals(row.entityType()))
                    && row.entityId() != null) {
                parseUuid(row.entityId()).ifPresent(ids::add);
            }
        }
        return ids;
    }

    List<Option> kindOptions() {
        return KINDS.stream().map(kind -> new Option(kind, kind(kind)))
                .sorted(java.util.Comparator.comparing(Option::label)).toList();
    }

    List<Row> rows(List<AuditRow> rows, Lookups lookups) {
        Names names = new Names(lookups);
        return rows.stream().map(row -> row(row, names, lookups.leaveTypes())).toList();
    }

    private Row row(AuditRow row, Names names, Map<UUID, String> leaveTypes) {
        JsonNode before = parse(row.beforeJson());
        JsonNode after = parse(row.afterJson());
        JsonNode current = after.isEmpty() ? before : after;
        boolean person = row.actorUserId() != null && !"SUPER_ADMIN".equals(row.actorRole());
        return new Row(
                row.id(),
                row.occurredAt(),
                actor(row, names),
                person && row.actorRole() != null ? message("role." + lower(row.actorRole()), humanize(row.actorRole())) : null,
                kind(row.entityType()),
                label(row.entityType(), row.entityId(), current, names, leaveTypes),
                change(row.action(), before, after, names),
                row.hasDiff());
    }

    private String actor(AuditRow row, Names names) {
        if (row.actorUserId() == null) {
            return message("actor.system", "System");
        }
        if ("SUPER_ADMIN".equals(row.actorRole())) {
            return message("actor.support", "Caderly support");
        }
        String name = names.ofUser(row.actorUserId());
        return name != null ? name : message("actor.unknown", "Former user");
    }

    private String kind(String entityType) {
        return message("kind." + entityType, humanize(entityType));
    }

    /** Who or what the record is, from the snapshot plus this page's lookups; "" when unknown. */
    private String label(String type, @Nullable String entityId, JsonNode json, Names names, Map<UUID, String> leaveTypes) {
        UUID id = entityId == null ? null : parseUuid(entityId).orElse(null);
        String owner = names.ofEmployee(text(json, "employeeId"));
        String leaveType = id == null ? null : leaveTypes.get(id);
        return switch (type) {
            case "Employee" -> firstNonBlank(
                    id == null ? null : names.ofEmployee(id.toString()),
                    join(" ", text(json, "firstName"), text(json, "lastName")));
            case "AppUser" -> firstNonBlank(id == null ? null : names.ofUser(id), text(json, "email"));
            case "DashboardLayout" -> firstNonBlank(names.ofUser(parseUuid(text(json, "userId")).orElse(null)));
            case "UserRole" -> firstNonBlank(value(text(json, "role")));
            case "EmployeeStatusHistory" -> firstNonBlank(value(text(json, "status")));
            case "LeaveBalance" -> join(" · ", owner, join(" ", leaveType, text(json, "year")));
            case "LeaveRequest" -> join(" · ", owner, join(", ", leaveType,
                    fmt.range(date(text(json, "startDate")), date(text(json, "endDate")))));
            case "PublicHoliday" -> join(" · ", text(json, "name"), dateText(text(json, "date")));
            case "EmployeeDocument" -> join(" · ", owner, text(json, "name"));
            default -> firstNonBlank(text(json, "name"));
        };
    }

    /**
     * Create and delete say so. An update names its first changed field, before → after, and counts
     * the rest; a status change stands alone because its other fields (decided at, decider) are the
     * same event's bookkeeping. Secrets are "[REDACTED]" on both sides, so they never show as changed.
     */
    private String change(Action action, JsonNode before, JsonNode after, Names names) {
        if (action == Action.CREATE) {
            return message("summary.created", "Created");
        }
        if (action == Action.DELETE) {
            return message("summary.deleted", "Deleted");
        }
        // A manual event (a Super Admin's workspace change) records only the new values.
        boolean afterOnly = before.isEmpty();
        List<String> changed = new ArrayList<>();
        for (String field : after.propertyNames()) {
            if (!BOOKKEEPING.contains(field) && (afterOnly || !after.get(field).equals(before.get(field)))) {
                changed.add(field);
            }
        }
        if (changed.isEmpty()) {
            return message("summary.updated", "Updated");
        }
        changed.sort(java.util.Comparator.comparing((String field) -> !"status".equals(field))
                .thenComparing(java.util.Comparator.naturalOrder()));
        String first = changed.getFirst();
        String line = message("field." + first, humanize(first)) + " "
                + (afterOnly ? "" : display(before.get(first), names) + " ") + "→ " + display(after.get(first), names);
        if (changed.size() == 1 || "status".equals(first)) {
            return line;
        }
        return line + " " + message("summary.more", "+" + (changed.size() - 1) + " more", changed.size() - 1);
    }

    private String display(@Nullable JsonNode node, Names names) {
        if (node == null || node.isNull()) {
            return "—";
        }
        if (node.isNumber()) {
            return fmt.days(node.decimalValue());
        }
        if (node.isBoolean()) {
            return message("value." + node.asBoolean(), node.asBoolean() ? "Yes" : "No");
        }
        String text = node.isString() ? node.asString() : node.toString();
        if (ISO_DATE.matcher(text).matches()) {
            return dateText(text);
        }
        if (text.length() > 10 && text.charAt(10) == 'T') {
            try {
                return fmt.recent(Instant.parse(text));
            } catch (DateTimeParseException notAnInstant) {
                // Fall through: an ordinary string that happens to look like one.
            }
        }
        var uuid = parseUuid(text);
        if (uuid.isPresent()) {
            String name = names.ofEmployee(text);
            return name != null ? name : firstNonBlank(names.ofUser(uuid.get()), "…");
        }
        if (ENUM_VALUE.matcher(text).matches()) {
            return value(text);
        }
        return text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH - 1) + "…" : text;
    }

    /** An enum constant in UI wording: "REJECTED" reads "Declined" (BRAND §3.2), others "Pending". */
    private @Nullable String value(@Nullable String constant) {
        return constant == null ? null : message("value." + lower(constant), humanize(constant));
    }

    private String dateText(@Nullable String iso) {
        LocalDate date = date(iso);
        return date == null ? "" : fmt.date(date);
    }

    private JsonNode parse(@Nullable String json) {
        if (json == null) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (JacksonException malformed) {
            return mapper.createObjectNode();
        }
    }

    private String message(String key, String fallback, Object... args) {
        return messages.getMessage("admin.audit-log." + key, args, fallback, LocaleContextHolder.getLocale());
    }

    private static @Nullable String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.isString() ? node.asString() : node.toString();
    }

    private static @Nullable LocalDate date(@Nullable String iso) {
        if (iso == null || !ISO_DATE.matcher(iso).matches()) {
            return null;
        }
        return LocalDate.parse(iso);
    }

    private static java.util.Optional<UUID> parseUuid(@Nullable String text) {
        if (text == null || text.length() != 36) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAUuid) {
            return java.util.Optional.empty();
        }
    }

    /** "LeaveBalance" → "Leave balance", "lastName" → "last name", "PENDING" → "Pending". */
    static String humanize(String identifier) {
        String words = ENUM_VALUE.matcher(identifier).matches()
                ? identifier.replace('_', ' ').toLowerCase(Locale.ROOT)
                : CAMEL_HUMP.matcher(identifier).replaceAll(" ").toLowerCase(Locale.ROOT);
        return Character.isUpperCase(identifier.charAt(0)) ? Character.toUpperCase(words.charAt(0)) + words.substring(1) : words;
    }

    private static String lower(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String join(String separator, @Nullable String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        return String.join(separator, present);
    }

    private static String firstNonBlank(@Nullable String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    /** A user reads as their employee's name, else their login email. */
    private static final class Names {
        private final Map<String, String> employees = new HashMap<>();
        private final Map<UUID, String> users = new HashMap<>();

        Names(Lookups lookups) {
            users.putAll(lookups.userEmails());
            for (EmployeeName employee : lookups.employees()) {
                employees.put(employee.employeeId().toString(), employee.fullName());
                if (employee.userId() != null) {
                    users.put(employee.userId(), employee.fullName());
                }
            }
        }

        @Nullable String ofEmployee(@Nullable String employeeId) {
            return employeeId == null ? null : employees.get(employeeId);
        }

        @Nullable String ofUser(@Nullable UUID userId) {
            return userId == null ? null : users.get(userId);
        }
    }
}
