package com.gadong.sfaas;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SfaasApplication {

    public static void main(String[] args) {
        // 시간대는 Asia/Seoul로 고정해요 (11/16 팀 결정). 빈 생성 전에 해야 서버가 UTC여도 시각이 밀리지 않아요
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(SfaasApplication.class, args);
    }
}
