package com.gadong.sfaas.global.util;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** 시간 규칙(API 명세서 공통): DB는 시간대 없는 TIMESTAMP에 KST로 저장하고, 응답에는 +09:00을 붙여요. */
public final class Kst {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);

    private Kst() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    public static OffsetDateTime of(LocalDateTime t) {
        return t == null ? null : t.atOffset(OFFSET);
    }

    /** JdbcTemplate 조회 값(드라이버마다 Timestamp 또는 LocalDateTime)을 LocalDateTime으로 바꿔요 */
    public static LocalDateTime local(Object jdbcValue) {
        if (jdbcValue == null) return null;
        if (jdbcValue instanceof LocalDateTime t) return t;
        if (jdbcValue instanceof Timestamp t) return t.toLocalDateTime();
        throw new IllegalArgumentException("시간 값이 아니에요: " + jdbcValue.getClass());
    }

    /** 요청으로 받은 시각(+09:00 등)을 DB에 저장하는 KST 시각으로 바꿔요 */
    public static LocalDateTime local(OffsetDateTime t) {
        return t.atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /** 기간 조회 from(포함)의 시작 시각 */
    public static LocalDateTime start(LocalDate from) {
        return from.atStartOfDay();
    }

    /** 기간 조회 to(포함) 다음 날 0시. 조건은 {@code < endExclusive(to)}로 써요 */
    public static LocalDateTime endExclusive(LocalDate to) {
        return to.plusDays(1).atStartOfDay();
    }

    /** 구간 길이(분). 끝나지 않은 구간(end = null)은 지금까지로 계산해요 */
    public static long minutes(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end == null ? now() : end).toMinutes();
    }
}
