package com.gadong.sfaas.monitoring.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 공정별 수집 테이블과 측정 컬럼 (schema.sql v0.3). 응답 키는 컬럼명을 camelCase로 써요(명세 M-01·E-01 비고). */
final class ProcessData {

    record Table(String process, String name, List<String> columns) {}

    static final List<Table> TABLES = List.of(
            new Table("SLITTING", "slitting_data", List.of("torque", "current", "voltage")),
            new Table("PRESS", "press_data", List.of("pressure_left", "pressure_right", "press_time", "pressure_total")),
            new Table("WELDING", "welding_data", List.of("press_force", "current", "voltage", "weld_time")),
            new Table("PAINTING", "painting_data", List.of("ph", "temperature", "current", "voltage")));

    private ProcessData() {}

    static Optional<Table> of(String process) {
        return TABLES.stream().filter(t -> t.process().equalsIgnoreCase(process)).findFirst();
    }

    static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return b.toString();
    }

    /** 조회한 행에서 측정 컬럼만 camelCase 키로 담아요. 값이 없는 항목은 빼요 */
    static Map<String, Object> values(Map<String, Object> row, Table table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String c : table.columns()) {
            Object v = row.get(c);
            if (v != null) values.put(camel(c), v);
        }
        return values;
    }
}
