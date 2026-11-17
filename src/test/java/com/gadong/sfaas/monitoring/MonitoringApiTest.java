package com.gadong.sfaas.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** H2(메모리 DB)에 v0.3.2 모양 데이터를 넣고 모니터링 API(M-01~M-06, M-08~M-12, E-01)를 확인해요. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MonitoringApiTest {

    private static final String DAY = "2026-11-10";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3), ('PAINTING','도장',5), ('INSPECTION','검사',6)",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('PT1','도장-1','PAINTING',null,1,true), ('PT2','도장-2','PAINTING',null,2,true), ('VI','검사-1','INSPECTION',null,1,true), "
                        + "('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR2','프레스A-2','PRESS','A',2,true), ('A-PR3','프레스A-3','PRESS','A',3,true), "
                        + "('B-PR1','프레스B-1','PRESS','B',1,true), ('B-PR2','프레스B-2','PRESS','B',2,true)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1'), ('B','B라인','PT1','PT1')",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_ALARM','알람',false)",
                "insert into downtime_event(downtime_id, equipment_id, status, code, started_at) values (1,'A-PR1','D','UP_ALARM','2026-11-10 09:10:00')",
                "insert into equipment_status_history(equipment_id, status, started_at, ended_at, downtime_id) values "
                        + "('A-PR1','R','2026-11-10 09:00:00','2026-11-10 09:10:00',null), ('A-PR1','D','2026-11-10 09:10:00',null,1), "
                        + "('A-PR2','I','2026-11-10 09:05:00',null,null), ('A-PR3','DC','2026-11-10 09:06:00',null,null), "
                        + "('B-PR1','R','2026-11-10 08:00:00',null,null), ('B-PR2','DC','2026-11-10 08:30:00',null,null)",
                "insert into press_data(equipment_id, measured_at, pressure_left, pressure_right, press_time, pressure_total, result) values "
                        + "('A-PR1','2026-11-10 09:00:01',275.0,271.3,551,546.3,'OK'), ('A-PR1','2026-11-10 09:00:03',287.0,271.0,552,558.0,'NG'), "
                        + "('A-PR1','2026-11-10 09:00:07',280.0,270.0,550,550.0,'OK')",
                "insert into threshold(threshold_id, equipment_id, item_name, item_label, unit, lower_limit, upper_limit, updated_at) values "
                        + "(3,'A-PR1','pressure_left','좌 압력','bar',270,285.3,'2026-11-01 00:00:00'), "
                        + "(4,'PT1','ph','pH',null,1.51,5.18,'2026-11-01 00:00:00')",
                "insert into users(user_id, login_id, password_hash, user_name, role) values (1,'u1','x','김정비','OPERATOR')",
                "insert into alarm(alarm_id, equipment_id, item_name, measured_value, lower_limit, upper_limit, level, message, raised_at, cleared_at, acked_by, acked_at) values "
                        + "(381,'A-PR1','pressure_left',287.1,270,285.3,'CRITICAL','좌 압력 상한 초과','2026-11-10 09:10:00',null,null,null), "
                        + "(382,'B-PR1',null,null,null,null,'WARNING','고장 신호','2026-11-10 09:15:00','2026-11-10 09:18:00',1,'2026-11-10 09:20:00')",
                "insert into inspection_result(inspection_id, equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "(9001,'VI','P-1','A','PT1','OK','2026-11-10 09:30:00'), (9002,'VI','P-2','B','PT1','NG','2026-11-10 09:31:00')");
    }

    @Test
    void m01StatusJoinsOpenSegmentReasonAndLastValues() throws Exception {
        mvc.perform(get("/api/monitoring/status").param("lineId", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[0].status").value("D"))
                .andExpect(jsonPath("$[0].since").value("2026-11-10T09:10:00+09:00"))
                .andExpect(jsonPath("$[0].reasonCode").value("UP_ALARM"))
                .andExpect(jsonPath("$[0].lastValues.pressureLeft").value(280.0))
                .andExpect(jsonPath("$[1].status").value("I"))
                .andExpect(jsonPath("$[1].reasonCode").isEmpty())
                .andExpect(jsonPath("$[2].status").value("DC"));
        mvc.perform(get("/api/monitoring/status"))
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[7].lineId").isEmpty()); // 공용 설비는 뒤에
        mvc.perform(get("/api/monitoring/status").param("lineId", "Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINE_NOT_FOUND"));
    }

    @Test
    void m02CountsRsidPerLine() throws Exception {
        mvc.perform(get("/api/monitoring/lines/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lineId").value("A"))
                .andExpect(jsonPath("$[0].D").value(1))
                .andExpect(jsonPath("$[0].I").value(1))
                .andExpect(jsonPath("$[0].DC").value(1))
                .andExpect(jsonPath("$[0].worstStatus").value("D"))
                .andExpect(jsonPath("$[1].R").value(1))
                .andExpect(jsonPath("$[1].DC").value(1))
                .andExpect(jsonPath("$[1].worstStatus").value("DC")); // D > DC > S > I > R
    }

    @Test
    void m03RawAndFiveSecondAverage() throws Exception {
        mvc.perform(get("/api/equipments/A-PR1/metrics").param("from", DAY).param("to", DAY).param("metric", "pressureLeft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(1))
                .andExpect(jsonPath("$.metrics[0].unit").value("bar"))
                .andExpect(jsonPath("$.metrics[0].limitHigh").value(285.3))
                .andExpect(jsonPath("$.metrics[0].points.length()").value(3))
                .andExpect(jsonPath("$.metrics[0].points[1].result").value("NG"));
        mvc.perform(get("/api/equipments/A-PR1/metrics").param("from", DAY).param("to", DAY)
                        .param("metric", "pressureLeft").param("interval", "5s"))
                .andExpect(jsonPath("$.metrics[0].points.length()").value(2))
                .andExpect(jsonPath("$.metrics[0].points[0].ts").value("2026-11-10T09:00:00+09:00"))
                .andExpect(jsonPath("$.metrics[0].points[0].value").value(281.0))
                .andExpect(jsonPath("$.metrics[0].points[0].result").value("NG"));
        mvc.perform(get("/api/equipments/A-PR1/metrics").param("from", DAY).param("to", DAY).param("interval", "1m"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/equipments/X-PR9/metrics").param("from", DAY).param("to", DAY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }

    @Test
    void m04m05m06Alarms() throws Exception {
        mvc.perform(get("/api/alarms/active").param("lineId", "A"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].alarmId").value(381))
                .andExpect(jsonPath("$[0].itemName").value("pressureLeft"))
                .andExpect(jsonPath("$[0].measuredValue").value(287.1))
                .andExpect(jsonPath("$[0].lowerLimit").value(270.0))
                .andExpect(jsonPath("$[0].upperLimit").value(285.3))
                .andExpect(jsonPath("$[0].clearedAt").isEmpty())
                .andExpect(jsonPath("$[0].occurredAt").value("2026-11-10T09:10:00+09:00"));
        mvc.perform(get("/api/alarms").param("from", DAY).param("to", DAY).param("page", "2").param("size", "1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].alarmId").value(381))
                .andExpect(jsonPath("$.items[0].ackedAt").isEmpty());
        mvc.perform(get("/api/alarms").param("from", DAY).param("to", DAY).param("equipmentId", "B-PR1"))
                .andExpect(jsonPath("$.items[0].itemName").isEmpty()) // 고장 신호 알람은 항목이 없어요
                .andExpect(jsonPath("$.items[0].clearedAt").value("2026-11-10T09:18:00+09:00"));
        mvc.perform(patch("/api/alarms/381/ack").contentType(MediaType.APPLICATION_JSON).content("{\"ackUserId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackUserId").value(1))
                .andExpect(jsonPath("$.ackedAt").exists());
        mvc.perform(patch("/api/alarms/382/ack").contentType(MediaType.APPLICATION_JSON).content("{\"ackUserId\":1}"))
                .andExpect(jsonPath("$.ackedAt").value("2026-11-10T09:20:00+09:00")); // 이미 확인된 알람은 그대로
        mvc.perform(patch("/api/alarms/381/ack").contentType(MediaType.APPLICATION_JSON).content("{\"ackUserId\":999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/alarms/381/ack"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/alarms/999/ack").contentType(MediaType.APPLICATION_JSON).content("{\"ackUserId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"));
    }

    @Test
    void m08InspectionsFilterAndPage() throws Exception {
        mvc.perform(get("/api/inspections").param("from", DAY).param("to", DAY))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].inspectionId").value(9002))
                .andExpect(jsonPath("$.items[0].productId").value("P-2"));
        mvc.perform(get("/api/inspections").param("from", DAY).param("to", DAY).param("lineId", "B").param("result", "NG"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].paintId").value("PT1"));
        mvc.perform(get("/api/inspections").param("from", DAY).param("to", DAY).param("result", "RECHECK"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/inspections").param("from", "2026-11-11").param("to", DAY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void m09ChangesCurrentPaintAndWritesHistory() throws Exception {
        mvc.perform(put("/api/lines/A/paint-route").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paintId\":\"PT2\",\"reason\":\"PT1 점검\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPaintId").value("PT2"))
                .andExpect(jsonPath("$.changedAt").exists());
        em.flush();
        assertThat(jdbc.queryForObject("select current_paint_id from line where line_id = 'A'", String.class)).isEqualTo("PT2");
        assertThat(jdbc.queryForObject("select from_paint_id || '>' || to_paint_id from paint_route_history", String.class))
                .isEqualTo("PT1>PT2");
        mvc.perform(put("/api/lines/A/paint-route").contentType(MediaType.APPLICATION_JSON).content("{\"paintId\":\"A-PR1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/lines/Z/paint-route").contentType(MediaType.APPLICATION_JSON).content("{\"paintId\":\"PT2\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void m10m11Thresholds() throws Exception {
        mvc.perform(get("/api/alarms/thresholds").param("process", "PRESS"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].equipmentId").value("A-PR1"))
                .andExpect(jsonPath("$[0].process").value("PRESS"))
                .andExpect(jsonPath("$[0].metric").value("pressureLeft"))
                .andExpect(jsonPath("$[0].high").value(285.3));
        mvc.perform(get("/api/alarms/thresholds").param("equipmentId", "PT1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].metric").value("ph"));
        mvc.perform(get("/api/alarms/thresholds").param("equipmentId", "X-PR9"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/alarms/thresholds/3").contentType(MediaType.APPLICATION_JSON).content("{\"low\":271,\"high\":286}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.high").value(286.0))
                .andExpect(jsonPath("$.updatedAt").exists());
        em.flush();
        assertThat(jdbc.queryForObject("select upper_limit from threshold where threshold_id = 3", Double.class)).isEqualTo(286.0);
        mvc.perform(put("/api/alarms/thresholds/3").contentType(MediaType.APPLICATION_JSON).content("{\"low\":300,\"high\":286}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/alarms/thresholds/999").contentType(MediaType.APPLICATION_JSON).content("{\"low\":1,\"high\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("THRESHOLD_NOT_FOUND"));
    }

    @Test
    void m12ProcessEquipments() throws Exception {
        mvc.perform(get("/api/monitoring/processes/PRESS/equipments").param("lineId", "A"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].process").value("PRESS"));
        mvc.perform(get("/api/monitoring/processes/MILLING/equipments"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void e01EquipmentDataPagesAcrossTables() throws Exception {
        mvc.perform(get("/api/equipment-data").param("from", DAY).param("to", DAY).param("size", "2"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].process").value("PRESS"))
                .andExpect(jsonPath("$.items[0].values.pressureLeft").value(280.0));
        mvc.perform(get("/api/equipment-data").param("from", DAY).param("to", DAY).param("size", "2").param("page", "2"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/equipment-data").param("from", DAY).param("to", DAY).param("result", "NG"))
                .andExpect(jsonPath("$.total").value(1));
    }
}
