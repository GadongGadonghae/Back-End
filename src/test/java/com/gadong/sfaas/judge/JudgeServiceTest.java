package com.gadong.sfaas.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gadong.sfaas.judge.service.ProcessJudgeService;
import com.gadong.sfaas.judge.service.StatusJudgeService;
import com.gadong.sfaas.push.PushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** H2로 임시 판정 흐름을 확인해요: 공정 행 OK 저장, 상태 이력은 바뀔 때만 1줄. */
@SpringBootTest
@Transactional
class JudgeServiceTest {

    @Autowired ProcessJudgeService processJudge;
    @Autowired StatusJudgeService statusJudge;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushService pushService;

    @BeforeEach
    void seed() {
        jdbc.batchUpdate(
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3), ('WELDING','용접',4)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR4','프레스A-4','PRESS','A',4,true), ('A-WD','용접A','WELDING','A',1,true)",
                // 실제 DB처럼 프레스는 설비마다 항목 하나 (PR1 좌 압력, PR4 가압 시간)
                "insert into threshold(threshold_id, equipment_id, item_name, item_label, unit, lower_limit, upper_limit, updated_at) values "
                        + "(1,'A-PR1','pressure_left','좌 압력','bar',270,285.3,CURRENT_TIMESTAMP), "
                        + "(2,'A-PR4','press_time','가압 시간','ms',540,560,CURRENT_TIMESTAMP), "
                        + "(3,'A-WD','current','전류','kA',14.52,15.07,CURRENT_TIMESTAMP)",
                "insert into press_data(equipment_id, measured_at, pressure_left, pressure_right, pressure_diff, press_time, result) values "
                        + "('A-PR4', CURRENT_TIMESTAMP, 275.0, 271.3, 3.7, 551, null), " // 네 값 다 범위 안 → OK
                        + "('A-PR1', CURRENT_TIMESTAMP, 280.0, null, null, null, null), " // PR1만 지남, 범위 안 → 기다림
                        + "('A-PR1', CURRENT_TIMESTAMP, 287.1, null, null, null, null), " // PR1 좌 압력 상한 초과 → 바로 NG
                        + "('A-PR4', CURRENT_TIMESTAMP, 275.0, 271.3, 3.7, 561, null)", // PR4 가압 시간 상한 초과 → NG
                "insert into welding_data(equipment_id, measured_at, current, result) values "
                        + "('A-WD', CURRENT_TIMESTAMP, 15.0, null), ('A-WD', CURRENT_TIMESTAMP, 15.3, null)",
                "insert into slitting_data(equipment_id, measured_at, result) values ('A-PR1', CURRENT_TIMESTAMP, null)"); // 기준 없음 → OK
    }

    @Test
    void processJudge_savesOk_andPushesProduction() {
        processJudge.judge();

        assertThat(jdbc.queryForList("select result from press_data order by id", String.class)).containsExactly("OK", null, "NG", "NG");
        assertThat(jdbc.queryForList("select result from welding_data order by id", String.class)).containsExactly("OK", "NG");
        assertThat(jdbc.queryForList("select result from slitting_data", String.class)).containsExactly("OK");
        verify(pushService).pushProduction(any());

        clearInvocations(pushService);
        processJudge.judge(); // 판정할 행이 없으면 보내지 않아요 (기다리는 프레스 행은 그대로)
        verify(pushService, never()).pushProduction(any());
    }

    @Test
    void statusJudge_savesOnlyOnChange() {
        statusJudge.judge();
        assertThat(historyCount()).isEqualTo(3); // 설비당 1줄
        verify(pushService, times(3)).pushStatus(any());

        clearInvocations(pushService);
        statusJudge.judge(); // 상태가 그대로면 저장·푸시 없음
        assertThat(historyCount()).isEqualTo(3);
        verify(pushService, never()).pushStatus(any());
    }

    @Test
    void statusJudge_followsPriority_andLinksDowntime() {
        // A-PR1 고장 신호, A-PR4 신호 없음, A-WD 신호 정상 + 앞(프레스) OK 1 − 용접 2 → 재공 없음
        jdbc.update("insert into equipment_signal(equipment_id, last_seen_at, fault) values ('A-PR1', CURRENT_TIMESTAMP, 1), ('A-WD', CURRENT_TIMESTAMP, 0)");
        processJudge.judge();
        statusJudge.judge();
        assertThat(status("A-PR1")).isEqualTo("D");
        assertThat(status("A-PR4")).isEqualTo("DC");
        assertThat(status("A-WD")).isEqualTo("I");
        assertThat(jdbc.queryForList("select code from downtime_event where ended_at is null order by equipment_id", String.class))
                .containsExactly("UP_BREAKDOWN", "UP_SHORTAGE");

        // 용접 앞에 OK 프레스 2개가 더 오면 R, 대기 이벤트는 닫혀요
        jdbc.update("insert into press_data(equipment_id, measured_at, pressure_left, pressure_right, pressure_diff, press_time, result) values "
                + "('A-PR4', CURRENT_TIMESTAMP, 1, 1, 1, 1, 'OK'), ('A-PR4', CURRENT_TIMESTAMP, 1, 1, 1, 1, 'OK')");
        statusJudge.judge();
        assertThat(status("A-WD")).isEqualTo("R");
        assertThat(jdbc.queryForObject("select count(*) from downtime_event where code = 'UP_SHORTAGE' and ended_at is not null", Integer.class)).isEqualTo(1);

        // 계획정지(S-02가 만든 이벤트)가 있으면 S로 그 이벤트에 연결, 고장(D)이 우선
        jdbc.update("insert into downtime_event(equipment_id, status, code, started_at, stop_group_id) values "
                + "('A-WD','S','PL_TEST', DATEADD('MINUTE', -1, CURRENT_TIMESTAMP), 1), ('A-PR1','S','PL_TEST', DATEADD('MINUTE', -1, CURRENT_TIMESTAMP), 1)");
        statusJudge.judge();
        assertThat(status("A-WD")).isEqualTo("S");
        assertThat(status("A-PR1")).isEqualTo("D");
        assertThat(jdbc.queryForObject("select d.code from equipment_status_history h join downtime_event d on d.downtime_id = h.downtime_id"
                + " where h.equipment_id = 'A-WD' and h.ended_at is null", String.class)).isEqualTo("PL_TEST");
    }

    private String status(String id) {
        return jdbc.queryForObject("select trim(status) from equipment_status_history where equipment_id = ? and ended_at is null", String.class, id);
    }

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from equipment_status_history", Integer.class);
    }
}
