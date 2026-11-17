package com.gadong.sfaas.push;

import com.gadong.sfaas.monitoring.dto.MonitoringDtos.EquipmentStatusResponse;
import com.gadong.sfaas.push.dto.PushDtos.AlarmMessage;
import com.gadong.sfaas.push.dto.PushDtos.MetricsMessage;
import com.gadong.sfaas.push.dto.PushDtos.ProductionMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** 판정 결과를 화면에 밀어 줘요 (W-01 ~ W-04). 판정(judge)이 DB에 저장한 뒤 불러요. 시각은 Kst.of()로 만든 +09:00 값을 넣어요. */
@Service
@RequiredArgsConstructor
public class PushService {
    private final SimpMessagingTemplate template;

    /** W-01: RSID가 바뀔 때 */
    public void pushStatus(EquipmentStatusResponse status) {
        template.convertAndSend("/topic/status", status);
    }

    /** W-02: 알람 발생·해제·확인 시 */
    public void pushAlarm(AlarmMessage alarm) {
        template.convertAndSend("/topic/alarms", alarm);
    }

    /** W-03: 비전 검사 1건마다 · 공정 NG 발생 시 */
    public void pushProduction(ProductionMessage production) {
        template.convertAndSend("/topic/production", production);
    }

    /** W-04: 1초마다, 상세 창을 연 설비만 */
    public void pushMetrics(String equipmentId, MetricsMessage metrics) {
        template.convertAndSend("/topic/metrics/" + equipmentId, metrics);
    }
}
