package com.gadong.sfaas.notify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** ReplanApiTest와 같은 데이터: 11/10 12:00 A-PR1 고장(12:40 해제) → 부족 98, B 49 · C 49 */
final class SlackFixture {
    private SlackFixture() {}

    static void seed(JdbcTemplate jdbc) {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq, cycle_time_sec) values ('SLITTING','슬리팅',2,16), ('PRESS','프레스',3,17), "
                        + "('WELDING','용접',4,17), ('PAINTING','도장',5,4), ('INSPECTION','검사',6,2)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values "
                        + "('A','A라인','PT1','PT1'), ('B','B라인','PT1','PT1'), ('C','C라인','PT2','PT2')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','A라인 프레스 1호기','PRESS','A',1,true), ('B-PR1','B라인 프레스 1호기','PRESS','B',1,true), "
                        + "('PT1','도장 1호기','PAINTING',null,1,true), ('PT2','도장 2호기','PAINTING',null,2,true), "
                        + "('VI','비전 검사 1호기','INSPECTION',null,1,true)",
                "insert into downtime_code(code, code_name, is_planned) values ('UP_BREAKDOWN','장비고장',false)",
                "insert into users(user_id, login_id, password_hash, user_name, role) values (1,'u1','x','김관리','MANAGER')",
                "insert into production_target(target_date, line_id, target_qty, created_at) values "
                        + "('2026-11-10','A',100,'2026-11-09 00:00:00'), ('2026-11-10','B',100,'2026-11-09 00:00:00'), "
                        + "('2026-11-10','C',100,'2026-11-09 00:00:00')",
                "insert into inspection_result(equipment_id, product_id, line_id, paint_equipment_id, result, inspected_at) values "
                        + "('VI','A-1','A','PT1','OK','2026-11-10 10:00:00'), ('VI','A-2','A','PT1','OK','2026-11-10 11:00:00'), "
                        + "('VI','B-1','B','PT1','OK','2026-11-10 11:30:00')",
                "insert into downtime_event(downtime_id, equipment_id, status, code, detail, started_at, ended_at) values "
                        + "(10,'A-PR1','D','UP_BREAKDOWN','압력 상한 초과 <!channel>','2026-11-10 12:00:00','2026-11-10 12:40:00')");
    }

    /** R-03으로 보고서를 만들고 reportId를 돌려줘요 */
    static String createReport(MockMvc mvc) throws Exception {
        String body = mvc.perform(post("/api/reassignments").contentType(MediaType.APPLICATION_JSON).content("{\"downtimeId\":10}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll("(?s).*?\"reportId\":(\\d+).*", "$1");
    }
}
