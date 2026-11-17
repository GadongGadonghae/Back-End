package com.gadong.sfaas.global.util;

import static com.gadong.sfaas.global.util.Checks.invalid;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** 집계 단위(day / week / month) 구간 계산. week는 월요일 시작이에요. */
public final class Periods {
    private Periods() {}

    public static void unit(String unit) {
        if (!"day".equals(unit) && !"week".equals(unit) && !"month".equals(unit)) throw invalid("unit은 day, week, month 중 하나입니다");
    }

    /** date가 속한 구간의 첫날 */
    public static LocalDate start(LocalDate date, String unit) {
        return switch (unit) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    /** 다음 구간의 첫날 */
    public static LocalDate next(LocalDate start, String unit) {
        return switch (unit) {
            case "week" -> start.plusWeeks(1);
            case "month" -> start.plusMonths(1);
            default -> start.plusDays(1);
        };
    }

    /** 응답의 period 값: day·week는 첫날(2026-11-17), month는 2026-11 */
    public static String label(LocalDate start, String unit) {
        return "month".equals(unit) ? start.toString().substring(0, 7) : start.toString();
    }
}
