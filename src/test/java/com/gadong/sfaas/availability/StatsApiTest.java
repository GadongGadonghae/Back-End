package com.gadong.sfaas.availability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생산량(M-07, M-13)·가동률(U-01~U-06)·양품률(U-11)·계획 대비(G-01) 집계를 손으로 계산한 값과 비교해요.
 *
 * <p>2026-11-10(화) 하루(86,400초)만 쓰고, 지난 날이라 "지금까지" 잘림이 없어요.
 * A라인: A-SL 정상 / A-PR1 09:00~10:00 고장 / A-WD 12:00~13:00 계획정지 + 12:30~13:30 고장(계획정지 안 30분은 손실 아님).
 * B라인은 정지 없음. 처리 시간은 슬리팅 16초, 프레스 17초, 용접 17초라 라인 능력은 1/17개/초예요.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsApiTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq, cycle_time_sec) values ('COIL','코일입고',1,0), ('SLITTING','슬리팅',2,16), "
                        + "('PRESS','프레스',3,17), ('WELDING','용접',4,17), ('PAINTING','도장',5,4), ('INSPECTION','검사',6,7)",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('PT1','도장-1','PAINTING',null,1,true), ('VI','검사-1','INSPECTION',null,1,true), "
                        + "('A-SL','슬리터A','SLITTING','A',1,true), ('A-PR1','프레스A-1','PRESS','A',1,true), ('A-WD','용접A','WELDING','A',1,true), "
                        + "('B-SL','슬리터B','SLITTING','B',1,true), ('B-PR1','프레스B-1','PRESS','B',1,true), ('B-WD','용접B','WELDING','B',1,true)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1'), ('B','B라인','PT1','PT1')",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false), ('PL_PM','예방보전',true)",
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at, ended_at, stop_group_id) values "
                        + "(1,'A-PR1','D','UP_BREAKDOWN','2026-11-10 09:00:00','2026-11-10 10:00:00',null), "
                        + "(2,'A-WD','S','PL_PM','2026-11-10 12:00:00','2026-11-10 13:00:00',7), "
                        + "(3,'A-WD','D','UP_BREAKDOWN','2026-11-10 12:30:00','2026-11-10 13:30:00',null)",
                // 검사: A 4개(비전 NG 1), B 2개 모두 도장 PT1
                "insert into inspection_result(inspection_id, equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "(1,'VI','P-1','A','PT1','OK','2026-11-10 10:00:00'), (2,'VI','P-2','A','PT1','OK','2026-11-10 10:01:00'), "
                        + "(3,'VI','P-3','A','PT1','OK','2026-11-10 10:02:00'), (4,'VI','P-4','A','PT1','NG','2026-11-10 10:03:00'), "
                        + "(5,'VI','P-5','B','PT1','OK','2026-11-10 10:04:00'), (6,'VI','P-6','B','PT1','OK','2026-11-10 10:05:00')",
                "insert into slitting_data(equipment_id, measured_at, result) values "
                        + "('A-SL','2026-11-10 08:00:00','OK'), ('A-SL','2026-11-10 08:00:20','OK'), ('A-SL','2026-11-10 08:00:40','OK'), "
                        + "('A-SL','2026-11-10 08:01:00','OK'), ('A-SL','2026-11-10 08:01:20','OK')",
                "insert into press_data(equipment_id, measured_at, result) values "
                        + "('A-PR1','2026-11-10 08:10:00','OK'), ('A-PR1','2026-11-10 08:10:20','OK'), ('A-PR1','2026-11-10 08:10:40','NG')",
                "insert into welding_data(equipment_id, measured_at, result) values ('A-WD','2026-11-10 08:20:00','OK')",
                "insert into painting_data(equipment_id, measured_at, line_id, result) values ('PT1','2026-11-10 08:30:00','B','NG')",
                "insert into coil_receipt(material_id, received_at, product_qty) values ('C-1','2026-11-09 08:00:00',20), ('C-2','2026-11-09 09:00:00',20)",
                "insert into production_target(target_date, line_id, target_qty, created_at) values ('2026-11-10','A',10,'2026-11-09 00:00:00')");
    }

    @Test
    void m07CountsByLineAndPaint() throws Exception {
        // 생산 8 = 검사 6 + 공정 불량 2(프레스 A 1, 도장 B 1). 양품 5 = 검사 6 − 비전 불량 1
        mvc.perform(get("/api/v1/production/counts").param("date", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.processNg").value(2))
                .andExpect(jsonPath("$.inspected").value(6))
                .andExpect(jsonPath("$.ngVision").value(1))
                .andExpect(jsonPath("$.ok").value(5))
                .andExpect(jsonPath("$.yieldRate").value(62.5))
                .andExpect(jsonPath("$.byGroup[0].key").value("A"))
                .andExpect(jsonPath("$.byGroup[0].total").value(5))
                .andExpect(jsonPath("$.byGroup[0].good").value(3))
                .andExpect(jsonPath("$.byGroup[0].ng").value(2))
                .andExpect(jsonPath("$.byGroup[1].key").value("B"))
                .andExpect(jsonPath("$.byGroup[1].total").value(3))
                .andExpect(jsonPath("$.byProcess.length()").value(0)); // lineId를 주지 않으면 비어요
        mvc.perform(get("/api/v1/production/counts").param("date", DAY).param("lineId", "A"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.byProcess[?(@.process=='PRESS')].produced").value(3))
                .andExpect(jsonPath("$.byProcess[?(@.process=='PRESS')].ng").value(1));
        mvc.perform(get("/api/v1/production/counts").param("date", DAY).param("groupBy", "paint"))
                .andExpect(jsonPath("$.byGroup.length()").value(1)) // 도장 앞에서 폐기된 불량(프레스 NG)은 빠져요
                .andExpect(jsonPath("$.byGroup[0].key").value("PT1"))
                .andExpect(jsonPath("$.byGroup[0].total").value(7))
                .andExpect(jsonPath("$.byGroup[0].good").value(5));
        mvc.perform(get("/api/v1/production/counts").param("groupBy", "shift")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/production/counts").param("lineId", "Z")).andExpect(status().isNotFound());
    }

    @Test
    void m13WipIsPreviousOkMinusNextRows() throws Exception {
        mvc.perform(get("/api/v1/production/wip"))
                .andExpect(jsonPath("$[0].process").value("COIL"))
                .andExpect(jsonPath("$[0].nextProcess").value("SLITTING"))
                .andExpect(jsonPath("$[0].wipQty").value(35)) // 코일 40개 − 슬리팅 5
                .andExpect(jsonPath("$[1].process").value("SLITTING"))
                .andExpect(jsonPath("$[1].wipQty").value(2)) // 슬리팅 OK 5 − 프레스 3
                .andExpect(jsonPath("$[2].process").value("PRESS"))
                .andExpect(jsonPath("$[2].wipQty").value(1)) // 프레스 OK 2 − 용접 1
                .andExpect(jsonPath("$[3].process").value("WELDING"))
                .andExpect(jsonPath("$[3].wipQty").value(0)) // 용접 OK 1 − 도장 1
                .andExpect(jsonPath("$[4].process").value("PAINTING"))
                .andExpect(jsonPath("$[4].nextProcess").value("INSPECTION"))
                .andExpect(jsonPath("$[4].wipQty").value(0)); // 음수는 0
        mvc.perform(get("/api/v1/production/wip").param("lineId", "A"))
                .andExpect(jsonPath("$.length()").value(4)) // 코일은 라인 구분이 없어 빠져요
                .andExpect(jsonPath("$[0].process").value("SLITTING"))
                .andExpect(jsonPath("$[0].wipQty").value(2))
                .andExpect(jsonPath("$[2].wipQty").value(1)); // 용접 OK 1 − 라인 A 도장 0
    }

    @Test
    void u01Summary() throws Exception {
        // 라인 설비 6대. 계획 가동: A 86400+86400+82800, B 3×86400 = 514,800초(8,580분). 비계획: 3,600 + 1,800(계획정지 안 30분 제외) = 5,400초(90분)
        mvc.perform(get("/api/v1/utilization/summary").param("from", DAY).param("to", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(95.0))
                .andExpect(jsonPath("$.availability").value(99.0))
                .andExpect(jsonPath("$.actual").value(99.0))
                .andExpect(jsonPath("$.plannedMin").value(8580))
                .andExpect(jsonPath("$.unplannedStopMin").value(90))
                .andExpect(jsonPath("$.lossQty").value(318)) // (3,600 + 1,800) ÷ 17 = 317.6
                .andExpect(jsonPath("$.quality").value(62.5))
                .andExpect(jsonPath("$.performance").isNumber())
                .andExpect(jsonPath("$.oee").isNumber());
        mvc.perform(get("/api/v1/utilization/summary").param("from", DAY).param("to", DAY).param("lineId", "B"))
                .andExpect(jsonPath("$.availability").value(100.0))
                .andExpect(jsonPath("$.unplannedStopMin").value(0));
        mvc.perform(get("/api/v1/utilization/summary").param("from", DAY)).andExpect(status().isBadRequest());
    }

    @Test
    void u02u03u04Breakdowns() throws Exception {
        mvc.perform(get("/api/v1/utilization/trend").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].period").value(DAY))
                .andExpect(jsonPath("$[0].actual").value(99.0));
        mvc.perform(get("/api/v1/utilization/trend").param("from", DAY).param("to", DAY).param("unit", "week"))
                .andExpect(jsonPath("$[0].period").value("2026-11-09")); // 월요일
        mvc.perform(get("/api/v1/utilization/trend").param("from", DAY).param("to", DAY).param("unit", "month"))
                .andExpect(jsonPath("$[0].period").value("2026-11"));
        mvc.perform(get("/api/v1/utilization/trend").param("from", DAY).param("to", DAY).param("unit", "year"))
                .andExpect(status().isBadRequest());

        // 프레스: 계획 172,800 − 비계획 3,600 = 97.9. 용접: 169,200 중 1,800 = 98.9
        mvc.perform(get("/api/v1/utilization/by-process").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.process=='PRESS')].actual").value(97.9))
                .andExpect(jsonPath("$[?(@.process=='PRESS')].unplannedStopMin").value(60))
                .andExpect(jsonPath("$[?(@.process=='WELDING')].actual").value(98.9))
                .andExpect(jsonPath("$[?(@.process=='WELDING')].unplannedStopMin").value(30))
                .andExpect(jsonPath("$[?(@.process=='PAINTING')].actual").value(100.0));

        mvc.perform(get("/api/v1/utilization/by-line").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].actual").value(97.9))
                .andExpect(jsonPath("$[0].stopCount").value(2))
                .andExpect(jsonPath("$[1].lineId").value("B"))
                .andExpect(jsonPath("$[1].actual").value(100.0))
                .andExpect(jsonPath("$[1].stopCount").value(0));
    }

    @Test
    void u05u06RankAndWeekday() throws Exception {
        mvc.perform(get("/api/v1/utilization/by-equipment").param("from", DAY).param("to", DAY).param("limit", "2"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1")) // 낮은 순: 82,800 ÷ 86,400 = 95.8
                .andExpect(jsonPath("$[0].actual").value(95.8))
                .andExpect(jsonPath("$[1].equipmentId").value("A-WD")) // 81,000 ÷ 82,800 = 97.8
                .andExpect(jsonPath("$[1].actual").value(97.8));
        mvc.perform(get("/api/v1/utilization/by-equipment").param("from", DAY).param("to", DAY).param("process", "slitting"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/utilization/by-equipment").param("from", DAY).param("to", DAY).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/utilization/by-weekday").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].weekday").value("TUE"))
                .andExpect(jsonPath("$[0].actual").value(99.0))
                .andExpect(jsonPath("$[0].stopCount").value(2));
    }

    @Test
    void u11YieldAndG01Achievement() throws Exception {
        mvc.perform(get("/api/v1/production/yield").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].period").value(DAY))
                .andExpect(jsonPath("$[0].key").doesNotExist())
                .andExpect(jsonPath("$[0].total").value(8))
                .andExpect(jsonPath("$[0].good").value(5))
                .andExpect(jsonPath("$[0].yieldRate").value(62.5));
        mvc.perform(get("/api/v1/production/yield").param("from", DAY).param("to", DAY).param("groupBy", "line"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("A"))
                .andExpect(jsonPath("$[0].yieldRate").value(60.0))
                .andExpect(jsonPath("$[1].key").value("B"))
                .andExpect(jsonPath("$[1].yieldRate").value(66.7));

        mvc.perform(get("/api/v1/production/summary").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].planQty").value(10))
                .andExpect(jsonPath("$[0].actualQty").value(5))
                .andExpect(jsonPath("$[0].achievementRate").value(50.0))
                .andExpect(jsonPath("$[1].lineId").value("B"))
                .andExpect(jsonPath("$[1].planQty").value(0))
                .andExpect(jsonPath("$[1].achievementRate").value(0.0));
        mvc.perform(get("/api/v1/production/summary").param("from", DAY).param("to", DAY).param("unit", "month").param("lineId", "A"))
                .andExpect(jsonPath("$[0].period").value("2026-11"));
    }
}
