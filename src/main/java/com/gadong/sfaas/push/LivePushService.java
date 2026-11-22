package com.gadong.sfaas.push;

import com.gadong.sfaas.global.util.Kst;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.FlowPositionsResponse;
import com.gadong.sfaas.monitoring.dto.MonitoringDtos.MetricsResponse;
import com.gadong.sfaas.monitoring.service.CoilStats;
import com.gadong.sfaas.monitoring.service.FlowStats;
import com.gadong.sfaas.monitoring.service.MonitoringService;
import com.gadong.sfaas.push.dto.PushDtos.FlowMessage;
import com.gadong.sfaas.push.dto.PushDtos.MetricsMessage;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 1초마다 계산해서 보내는 채널 (11/22): W-04 설비 공정 값, W-05 3D 물류.
 * 구독한 화면이 있을 때만 계산해요. 계산은 REST(M-03 · M-16 · M-17)와 같은 함수를 써서 값이 같아요.
 */
@Service
@RequiredArgsConstructor
public class LivePushService {
    private static final Logger log = LoggerFactory.getLogger(LivePushService.class);

    private final LiveSubscriptions subscriptions;
    private final PushService pushService;
    private final FlowStats flowStats;
    private final CoilStats coilStats;
    private final MonitoringService monitoringService;

    /** 설비마다 마지막으로 보낸 점 시각. 처음 구독되면 그 시각부터 보내요 (그 앞 점은 화면이 M-03으로 받아요) */
    private final Map<String, OffsetDateTime> lastSent = new ConcurrentHashMap<>();

    /** 스케줄러가 1초마다 불러요 */
    public void pushSubscribed() {
        if (subscriptions.has(PushService.FLOW)) {
            try {
                pushFlow();
            } catch (RuntimeException e) {
                log.warn("물류 푸시 실패: {}", e.getMessage());
            }
        }
        Set<String> equipmentIds = subscriptions.suffixes(PushService.METRICS_PREFIX);
        lastSent.keySet().retainAll(equipmentIds); // 아무도 안 보는 설비는 잊어요 → 다시 구독하면 그 시각부터
        for (String equipmentId : equipmentIds) {
            try {
                pushMetrics(equipmentId);
            } catch (RuntimeException e) {
                log.debug("설비 {} 공정 값 푸시 건너뜀: {}", equipmentId, e.getMessage()); // 없는 설비 ID 등
            }
        }
    }

    /** W-05: M-17 제품 위치 + M-16 코일 재고(제품 수) */
    void pushFlow() {
        FlowPositionsResponse positions = flowStats.flowPositions();
        int coilStockQty = coilStats.today(null).stockQty();
        pushService.pushFlow(new FlowMessage(positions.at(), positions.products(), coilStockQty));
    }

    /** W-04: 지난번 보낸 뒤 새로 생긴 점만 (M-03 after와 같음). 새 점이 없으면 보내지 않아요 */
    void pushMetrics(String equipmentId) {
        OffsetDateTime after = lastSent.get(equipmentId);
        if (after == null) {
            lastSent.put(equipmentId, Kst.of(Kst.now()));
            return;
        }
        LocalDate today = Kst.now().toLocalDate();
        MetricsResponse m = monitoringService.metrics(equipmentId, today, today, null, "raw", after);
        OffsetDateTime newest = m.metrics().stream()
                .flatMap(s -> s.points().stream())
                .map(MetricsResponse.Point::ts)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (newest == null) return;
        lastSent.put(equipmentId, newest);
        pushService.pushMetrics(equipmentId, new MetricsMessage(equipmentId, newest, m.metrics()));
    }
}
