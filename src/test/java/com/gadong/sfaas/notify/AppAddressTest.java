package com.gadong.sfaas.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppAddressTest {

    @Test
    void 적은_주소는_그대로() {
        assertThat(AppAddress.resolve("http://192.168.0.50/")).isEqualTo("http://192.168.0.50/");
        assertThat(AppAddress.resolve("")).isEmpty();
        assertThat(AppAddress.resolve(null)).isEmpty();
    }

    @Test
    void auto는_지금_네트워크_주소_네트워크가_없으면_빈_글() {
        assertThat(AppAddress.resolve("auto")).matches("^$|http://[0-9a-f.:%]+:5173");
        assertThat(AppAddress.resolve("auto:8081")).matches("^$|http://[0-9a-f.:%]+:8081");
    }
}
