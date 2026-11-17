package com.gadong.sfaas.global.util;

import java.time.LocalDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** 조회 SQL을 조건에 따라 조립할 때 쓰는 도우미. 값은 항상 바인딩 파라미터로 넘겨요. */
public final class Sql {
    private Sql() {}

    /** :from(포함) ~ :to(제외) 기간 파라미터 */
    public static MapSqlParameterSource period(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("from", Kst.start(from)).addValue("to", Kst.endExclusive(to));
    }

    /** :limit, :offset 페이지 파라미터. page는 1부터 */
    public static MapSqlParameterSource paging(MapSqlParameterSource p, int page, int size) {
        return p.addValue("limit", size).addValue("offset", (long) (page - 1) * size);
    }

    /** 값이 있을 때만 {@code AND column = :name} 조건을 붙여요. column에는 코드에 적힌 컬럼명만 넘겨요 */
    public static void eq(StringBuilder sql, MapSqlParameterSource p, String column, String name, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" = :").append(name);
        p.addValue(name, value);
    }
}
