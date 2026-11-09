package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.tenant.TenantFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * One place for how the app shows dates and day counts, called from templates as {@code @fmt}
 * (BRAND §3: real dates a person can read, e.g. "Mon 17 – Fri 21 Aug 2026", never ISO strings).
 * Instants are shown in the tenant's timezone, the same zone the audit log already uses.
 */
@Component("fmt")
public class DisplayFormats {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TenantFacade tenants;
    private final MessageSource messages;
    private final Clock clock;

    DisplayFormats(TenantFacade tenants, MessageSource messages, Clock clock) {
        this.tenants = tenants;
        this.messages = messages;
        this.clock = clock;
    }

    /** Day counts are numeric(5,2) in the database; "5.00" reads as noise, "4.5" stays exact. */
    public String days(@Nullable BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    public String date(@Nullable LocalDate date) {
        return date == null ? "—" : DATE.withLocale(LocaleContextHolder.getLocale()).format(date);
    }

    /** Today's date in the tenant's timezone, e.g. "Sat 7 Nov 2026" under the home greeting. */
    public String today() {
        return date(LocalDate.now(clock.withZone(tenants.currentTimezone())));
    }

    /** The calendar date an instant falls on in the tenant's timezone (e.g. a file's upload). */
    public String dateOf(@Nullable Instant instant) {
        return instant == null ? "—" : date(instant.atZone(tenants.currentTimezone()).toLocalDate());
    }

    /** "Mon 17 – Fri 21 Aug 2026"; the month and year are said once when both ends share them. */
    public String range(@Nullable LocalDate start, @Nullable LocalDate end) {
        if (start == null || end == null || start.equals(end)) {
            return date(start == null ? end : start);
        }
        var locale = LocaleContextHolder.getLocale();
        DateTimeFormatter from;
        if (start.getYear() != end.getYear()) {
            from = DATE;
        } else if (start.getMonth() != end.getMonth()) {
            from = DAY_MONTH;
        } else {
            from = DAY;
        }
        return from.withLocale(locale).format(start) + " – " + DATE.withLocale(locale).format(end);
    }

    public String dateTime(@Nullable ZonedDateTime moment) {
        return moment == null ? "—" : DATE_TIME.withLocale(LocaleContextHolder.getLocale()).format(moment);
    }

    /** "Today, 17:04" / "Yesterday, 09:12" for recent moments, otherwise the full date and time. */
    public String recent(@Nullable Instant instant) {
        if (instant == null) {
            return "—";
        }
        var locale = LocaleContextHolder.getLocale();
        ZonedDateTime moment = instant.atZone(tenants.currentTimezone());
        LocalDate today = LocalDate.now(clock.withZone(moment.getZone()));
        if (moment.toLocalDate().equals(today)) {
            return messages.getMessage("common.today-at", new Object[] {TIME.format(moment)}, locale);
        }
        if (moment.toLocalDate().equals(today.minusDays(1))) {
            return messages.getMessage("common.yesterday-at", new Object[] {TIME.format(moment)}, locale);
        }
        return dateTime(moment);
    }
}
