package com.gadong.sfaas.push;

import com.gadong.sfaas.monitoring.dto.MonitoringDtos.EquipmentStatusResponse;
import com.gadong.sfaas.push.dto.PushDtos.AlarmMessage;
import com.gadong.sfaas.push.dto.PushDtos.FlowMessage;
import com.gadong.sfaas.push.dto.PushDtos.MetricsMessage;
import com.gadong.sfaas.push.dto.PushDtos.ProductionMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** 판정 결과를 화면에 밀어 줘요 (W-01 ~ W-05). 판정(judge)이 DB에 저장한 뒤 불러요. 시각은 Kst.of()로 만든 +09:00 값을 넣어요. */
@Service
@RequiredArgsConstructor
public class PushService {
    public static final String STATUS = "/topic/status";
    public static final String ALARMS = "/topic/alarms";
    public static final String PRODUCTION = "/topic/production";
    public static final String METRICS_PREFIX = "/topic/metrics/";
    public static final String FLOW = "/topic/flow";

    private final SimpMessagingTemplate template;

    /** W-01: RSID가 바뀔 때 */
    public void pushStatus(EquipmentStatusResponse status) {
        template.convertAndSend(STATUS, status);
    }

    /** W-02: 알람 발생·해제·확인 시 */
    public void pushAlarm(AlarmMessage alarm) {
        template.convertAndSend(ALARMS, alarm);
    }

    /** W-03: 비전 검사 1건마다 · 공정 NG 발생 시 */
    public void pushProduction(ProductionMessage production) {
        template.convertAndSend(PRODUCTION, production);
    }

    /** W-04: 1초마다, 상세 창을 연 설비만 (LivePushService) */
    public void pushMetrics(String equipmentId, MetricsMessage metrics) {
        template.convertAndSend(METRICS_PREFIX + equipmentId, metrics);
    }

    /** W-05: 1초마다, 3D 물류를 보는 화면이 있을 때만 (LivePushService) */
    public void pushFlow(FlowMessage flow) {
        template.convertAndSend(FLOW, flow);
    }
}
