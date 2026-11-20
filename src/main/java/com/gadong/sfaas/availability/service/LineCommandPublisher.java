package com.gadong.sfaas.availability.service;

import com.gadong.sfaas.global.util.Kst;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 라인 가동 명령 보내기 (MQTT {ctl}/line). 추가 주문 배정을 '승인하고 적용'하면 새로 켤 라인에 start를 보내요.
 * 지금은 재생기(player.py --hold)가 받아서 멈춰 둔 라인의 데이터를 보내기 시작해요. 실설비라면 PLC 쪽이 받는 자리예요.
 * 브로커에 닿지 못해도 예외를 던지지 않고 false (적용 결과에 "전달 못 함"으로 보여 줘요)
 */
@Component
public class LineCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(LineCommandPublisher.class);

    private final String brokerUrl;
    private final String topic;

    public LineCommandPublisher(@Value("${mqtt.broker-url:tcp://localhost:1883}") String brokerUrl,
                                @Value("${mqtt.topic-ctl:sfaas/ctl}") String ctl) {
        this.brokerUrl = brokerUrl;
        this.topic = ctl.replaceAll("/+$", "") + "/line";
    }

    /** action = start(가동) · hold(멈춤). 보냈으면 true */
    public boolean send(String lineId, String action) {
        String body = "{\"lineId\":\"" + lineId + "\",\"action\":\"" + action + "\",\"ts\":\"" + Kst.of(Kst.now()) + "\"}";
        MqttClient client = null;
        try {
            client = new MqttClient(brokerUrl, "sfaas-be-line-" + System.nanoTime(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setConnectionTimeout(3);
            options.setCleanSession(true);
            client.connect(options);
            MqttMessage message = new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(topic, message);
            log.info("라인 명령 보냄: {} {}", topic, body);
            return true;
        } catch (Exception e) {
            log.warn("라인 명령을 보내지 못했습니다 ({} {}): {}", topic, body, e.getMessage());
            return false;
        } finally {
            if (client != null) {
                try {
                    if (client.isConnected()) client.disconnect();
                    client.close();
                } catch (Exception ignored) {
                    // 닫다가 난 오류는 무시
                }
            }
        }
    }
}
