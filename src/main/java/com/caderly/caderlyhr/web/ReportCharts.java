package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.reports.ReportService.HeadcountRow;
import com.caderly.caderlyhr.reports.ReportService.UtilizationRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * View models for the report charts (PRD FR-10.2 stacked bar per department, FR-10.3 monthly
 * headcount line). Geometry is computed here, as percentages, so templates only place elements:
 * no chart library, nothing to load past the CSP, and the numbers are unit-testable.
 */
public final class ReportCharts {

    /** Categorical slots in caderly.css (--chart-1..6); a 7th leave type folds into "Other". */
    static final int SLOTS = 6;

    private ReportCharts() {}

    public record Segment(String leaveType, int slot, BigDecimal days) {}

    public record Bar(String department, BigDecimal total, double totalPct, List<Segment> segments) {}

    public record LegendItem(String leaveType, int slot) {}

    public record UtilizationChart(List<Bar> bars, List<LegendItem> legend) {}

    public record Point(LocalDate month, long count, double x, double y, boolean showYear) {}

    public record Tick(long value, double y) {}

    public record HeadcountChart(List<Point> points, List<Tick> ticks, String linePath, String areaPath) {}

    /**
     * One bar per department, segments per leave type. A type's slot is its position in the
     * tenant's full, name-ordered leave-type list, so its colour follows the type and never
     * changes when a filter removes other types (slot 0 = "Other").
     */
    public static UtilizationChart utilization(
            List<UtilizationRow> rows, List<String> allLeaveTypes, String noDepartment, String other) {
        Map<String, Map<String, BigDecimal>> byDepartment = new LinkedHashMap<>();
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (UtilizationRow row : rows) {
            String department = row.departmentName() == null ? noDepartment : row.departmentName();
            int index = allLeaveTypes.indexOf(row.leaveTypeName());
            int slot = index >= 0 && index < SLOTS ? index + 1 : 0;
            String series = slot == 0 ? other : row.leaveTypeName();
            slots.put(series, slot);
            byDepartment.computeIfAbsent(department, d -> new LinkedHashMap<>()).merge(series, row.daysUsed(), BigDecimal::add);
        }
        Comparator<Map.Entry<String, Integer>> bySlot =
                Comparator.comparing(e -> e.getValue() == 0 ? Integer.MAX_VALUE : e.getValue());

        List<Bar> unscaled = new ArrayList<>();
        byDepartment.forEach((department, days) -> {
            List<Segment> segments = slots.entrySet().stream()
                    .filter(e -> days.containsKey(e.getKey()))
                    .sorted(bySlot)
                    .map(e -> new Segment(e.getKey(), e.getValue(), days.get(e.getKey())))
                    .toList();
            BigDecimal total = segments.stream().map(Segment::days).reduce(BigDecimal.ZERO, BigDecimal::add);
            unscaled.add(new Bar(department, total, 0, segments));
        });
        BigDecimal max = unscaled.stream().map(Bar::total).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        List<Bar> bars = unscaled.stream()
                .sorted(Comparator.comparing(Bar::total).reversed().thenComparing(Bar::department))
                .map(b -> new Bar(b.department(), b.total(), pct(b.total(), max), b.segments()))
                .toList();
        List<LegendItem> legend = slots.entrySet().stream()
                .sorted(bySlot)
                .map(e -> new LegendItem(e.getKey(), e.getValue()))
                .toList();
        return new UtilizationChart(bars, legend);
    }

    /** Total active employees per month (departments summed) as one line, y from the top in %. */
    public static HeadcountChart headcount(List<HeadcountRow> rows) {
        TreeMap<LocalDate, Long> perMonth = new TreeMap<>();
        rows.forEach(row -> perMonth.merge(row.month(), row.activeCount(), Long::sum));
        long max = perMonth.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long step = niceStep(max);
        long top = step * 4;

        List<Point> points = new ArrayList<>();
        int n = perMonth.size();
        int i = 0;
        for (var entry : perMonth.entrySet()) {
            double x = n == 1 ? 50.0 : round2(100.0 * i / (n - 1));
            double y = round2(100.0 - 100.0 * entry.getValue() / top);
            boolean showYear = i == 0 || entry.getKey().getMonthValue() == 1;
            points.add(new Point(entry.getKey(), entry.getValue(), x, y, showYear));
            i++;
        }
        List<Tick> ticks = new ArrayList<>();
        for (int t = 0; t <= 4; t++) {
            ticks.add(new Tick(step * t, round2(100.0 - 25.0 * t)));
        }
        StringBuilder line = new StringBuilder();
        for (int p = 0; p < points.size(); p++) {
            line.append(p == 0 ? "M" : " L").append(fmt(points.get(p).x())).append(' ').append(fmt(points.get(p).y()));
        }
        String area = points.isEmpty() ? "" : line + " L" + fmt(points.getLast().x()) + " 100 L" + fmt(points.getFirst().x()) + " 100 Z";
        return new HeadcountChart(points, ticks, line.toString(), area);
    }

    /**
     * The smallest 1/2/3/5 × 10^k step whose four steps clear the maximum (never below 1). Strictly
     * above, so the line never sits on the top gridline where its end label would hit the title.
     */
    static long niceStep(long max) {
        if (max <= 0) {
            return 1;
        }
        double raw = max / 4.0;
        long magnitude = (long) Math.pow(10, Math.floor(Math.log10(raw)));
        for (long factor : new long[] {1, 2, 3, 5, 10}) {
            long step = Math.max(1, factor * magnitude);
            if (step * 4 > max) {
                return step;
            }
        }
        return 10 * magnitude;
    }

    private static double pct(BigDecimal value, BigDecimal max) {
        return max.signum() == 0 ? 0 : round2(value.doubleValue() * 100.0 / max.doubleValue());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
