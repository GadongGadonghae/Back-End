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
                "insert into process(process_id, process_name, seq) values ('PRESS','프레스',3)",
                "insert into line(line_id, line_name, default_paint_id, current_paint_id) values ('A','A라인','PT1','PT1')",
                "insert into equipment(equipment_id, equipment_name, process_id, line_id, seq_no, is_active) values "
                        + "('A-PR1','프레스A-1','PRESS','A',1,true), ('A-PR4','프레스A-4','PRESS','A',4,true)",
                "insert into press_data(equipment_id, measured_at, pressure_left, pressure_right, press_time, pressure_total, result) values "
                        + "('A-PR4', CURRENT_TIMESTAMP, 275.0, 271.3, 551, 546.3, null)");
    }

    @Test
    void processJudge_savesOk_andPushesProduction() {
        processJudge.judge();

        assertThat(jdbc.queryForList("select result from press_data", String.class)).containsExactly("OK");
        verify(pushService).pushProduction(any());

        clearInvocations(pushService);
        processJudge.judge(); // 판정할 행이 없으면 보내지 않아요
        verify(pushService, never()).pushProduction(any());
    }

    @Test
    void statusJudge_savesOnlyOnChange() {
        statusJudge.judge();
        assertThat(historyCount()).isEqualTo(2); // 설비당 1줄
        verify(pushService, times(2)).pushStatus(any());

        clearInvocations(pushService);
        statusJudge.judge(); // 상태가 그대로면 저장·푸시 없음
        assertThat(historyCount()).isEqualTo(2);
        verify(pushService, never()).pushStatus(any());
    }

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from equipment_status_history", Integer.class);
    }
}
