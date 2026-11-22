package com.gadong.sfaas.notify;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 슬랙 "보고서 보기" 버튼 주소 (11/22). slack.app-base-url이 auto면 보낼 때마다 이 컴퓨터의 지금 네트워크 주소를 찾아
 * http://주소:5173 을 써요 (auto:8081처럼 포트를 바꿀 수 있어요). 와이파이가 바뀌어도 설정을 고치지 않게요.
 */
final class AppAddress {
    private static final int FRONT_PORT = 5173;

    private AppAddress() {}

    /** auto가 아니면 적은 그대로. 네트워크가 없으면 빈 글 (버튼을 넣지 않음) */
    static String resolve(String configured) {
        String c = configured == null ? "" : configured.trim();
        if (!c.equals("auto") && !c.startsWith("auto:")) return c;
        int port = c.equals("auto") ? FRONT_PORT : Integer.parseInt(c.substring(5).trim());
        String ip = outboundIp();
        return ip == null ? "" : "http://" + ip + ":" + port;
    }

    /** 밖으로 나갈 때 쓰는 주소. UDP connect는 길만 정하고 아무것도 보내지 않아요 (가상 네트워크 카드 주소를 고르지 않게) */
    private static String outboundIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress("8.8.8.8", 53));
            InetAddress a = s.getLocalAddress();
            return a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() ? null : a.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
