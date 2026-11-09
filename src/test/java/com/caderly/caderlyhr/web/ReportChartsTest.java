package com.caderly.caderlyhr.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caderly.caderlyhr.reports.ReportService.HeadcountRow;
import com.caderly.caderlyhr.reports.ReportService.UtilizationRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportChartsTest {

    private static final List<String> TYPES = List.of("Compassionate", "Sick", "Study", "Unpaid", "Vacation", "WFH", "Zebra");

    private static UtilizationRow row(String dept, String type, String days) {
        return new UtilizationRow("Someone", dept, type, new BigDecimal(days), 1);
    }

    @Test
    void utilization_stacksLeaveTypesPerDepartment_sortedByTotalDescending() {
        var chart = ReportCharts.utilization(
                List.of(row("Ops", "Vacation", "2"), row("Eng", "Vacation", "5"), row("Eng", "Sick", "1"), row("Eng", "Vacation", "3")),
                TYPES, "No department", "Other");

        assertThat(chart.bars()).extracting(ReportCharts.Bar::department).containsExactly("Eng", "Ops");
        ReportCharts.Bar eng = chart.bars().getFirst();
        assertThat(eng.total()).isEqualByComparingTo("9");
        assertThat(eng.totalPct()).isEqualTo(100.0);
        assertThat(eng.segments()).extracting(ReportCharts.Segment::leaveType).containsExactly("Sick", "Vacation");
        assertThat(eng.segments()).extracting(ReportCharts.Segment::days)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("1"), new BigDecimal("8"));
        assertThat(chart.bars().get(1).totalPct()).isCloseTo(22.2, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void utilization_slotFollowsTheLeaveTypeNotItsRank_soAFilterNeverRepaints() {
        var all = ReportCharts.utilization(List.of(row("Eng", "Sick", "1"), row("Eng", "Vacation", "2")), TYPES, "-", "Other");
        var filtered = ReportCharts.utilization(List.of(row("Eng", "Vacation", "2")), TYPES, "-", "Other");

        assertThat(all.legend()).extracting(ReportCharts.LegendItem::slot).containsExactly(2, 5);
        assertThat(filtered.legend()).extracting(ReportCharts.LegendItem::slot).containsExactly(5);
    }

    @Test
    void utilization_typesBeyondTheSixthSlot_foldIntoOneOtherSegment() {
        var chart = ReportCharts.utilization(
                List.of(row("Eng", "WFH", "1"), row("Eng", "Zebra", "2"), row("Eng", "Sick", "1")), TYPES, "-", "Other");

        assertThat(chart.bars().getFirst().segments()).extracting(ReportCharts.Segment::leaveType, ReportCharts.Segment::slot)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Sick", 2), org.assertj.core.groups.Tuple.tuple("WFH", 6),
                        org.assertj.core.groups.Tuple.tuple("Other", 0));
        assertThat(chart.legend()).extracting(ReportCharts.LegendItem::leaveType).containsExactly("Sick", "WFH", "Other");
    }

    @Test
    void utilization_rowWithoutDepartment_isLabelled() {
        var chart = ReportCharts.utilization(List.of(row(null, "Sick", "1")), TYPES, "No department", "Other");
        assertThat(chart.bars().getFirst().department()).isEqualTo("No department");
    }

    @Test
    void utilization_withNoRows_isEmpty() {
        assertThat(ReportCharts.utilization(List.of(), TYPES, "-", "Other").bars()).isEmpty();
    }

    @Test
    void headcount_sumsDepartmentsPerMonth_andScalesToNiceTicks() {
        LocalDate jan = LocalDate.of(2026, 1, 1);
        var chart = ReportCharts.headcount(List.of(
                new HeadcountRow(jan, "Eng", 4), new HeadcountRow(jan, "Ops", 3),
                new HeadcountRow(jan.plusMonths(1), "Eng", 5), new HeadcountRow(jan.plusMonths(1), "Ops", 4)));

        assertThat(chart.points()).extracting(ReportCharts.Point::count).containsExactly(7L, 9L);
        assertThat(chart.ticks()).extracting(ReportCharts.Tick::value).containsExactly(0L, 3L, 6L, 9L, 12L);
        assertThat(chart.points().getFirst().x()).isEqualTo(0.0);
        assertThat(chart.points().getLast().x()).isEqualTo(100.0);
        assertThat(chart.points().getLast().y()).isEqualTo(25.0);
        assertThat(chart.linePath()).startsWith("M0.00 41.67").contains("L100.00 25.00");
        assertThat(chart.areaPath()).endsWith("L100.00 100 L0.00 100 Z");
    }

    @Test
    void headcount_singleMonth_sitsInTheMiddle_andYearIsShownOnTheFirstLabel() {
        var chart = ReportCharts.headcount(List.of(new HeadcountRow(LocalDate.of(2026, 8, 1), null, 2)));
        assertThat(chart.points().getFirst().x()).isEqualTo(50.0);
        assertThat(chart.points().getFirst().showYear()).isTrue();
    }

    @Test
    void headcount_allZero_stillHasAnAxis() {
        var chart = ReportCharts.headcount(List.of(new HeadcountRow(LocalDate.of(2026, 8, 1), null, 0)));
        assertThat(chart.ticks()).extracting(ReportCharts.Tick::value).containsExactly(0L, 1L, 2L, 3L, 4L);
    }

    @Test
    void niceStep_leavesHeadroomAboveTheMaximum() {
        assertThat(ReportCharts.niceStep(4)).isEqualTo(2);
        assertThat(ReportCharts.niceStep(9)).isEqualTo(3);
        assertThat(ReportCharts.niceStep(40)).isEqualTo(20);
        assertThat(ReportCharts.niceStep(130)).isEqualTo(50);
    }
}
