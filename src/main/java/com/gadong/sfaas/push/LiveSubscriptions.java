package com.gadong.sfaas.push;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * 지금 어느 화면이 어느 채널을 구독 중인지 세요 (11/22).
 * W-04 · 물류처럼 1초마다 계산해서 보내는 채널은 보는 화면이 없으면 계산도 하지 않으려고 둬요.
 */
@Component
public class LiveSubscriptions {

    /** 연결(sessionId) → 구독 번호(subscriptionId) → 구독 주소 */
    private final Map<String, Map<String, String>> sessions = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        if (h.getSessionId() == null || h.getSubscriptionId() == null || h.getDestination() == null) return;
        sessions.computeIfAbsent(h.getSessionId(), k -> new ConcurrentHashMap<>()).put(h.getSubscriptionId(), h.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        if (h.getSessionId() == null || h.getSubscriptionId() == null) return;
        Map<String, String> subs = sessions.get(h.getSessionId());
        if (subs != null) subs.remove(h.getSubscriptionId());
    }

    /** 연결이 끊기면(화면 닫기·새로고침·네트워크) 그 연결의 구독을 모두 지워요 */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    /** 이 주소를 구독한 화면이 하나라도 있나 */
    public boolean has(String destination) {
        return sessions.values().stream().anyMatch(subs -> subs.containsValue(destination));
    }

    /** prefix로 시작하는 구독 주소의 나머지 부분. 예: "/topic/metrics/" → 구독 중인 설비 ID들 */
    public Set<String> suffixes(String prefix) {
        return sessions.values().stream()
                .flatMap(subs -> subs.values().stream())
                .filter(d -> d.startsWith(prefix) && d.length() > prefix.length())
                .map(d -> d.substring(prefix.length()))
                .collect(Collectors.toSet());
    }
}
