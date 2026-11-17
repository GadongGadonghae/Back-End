package com.gadong.sfaas.global.util;

import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import java.time.LocalDate;

/** 여러 API가 같이 쓰는 요청 값 검사. 실패하면 INVALID_REQUEST(400)예요. */
public final class Checks {
    private static final int MAX_SIZE = 100;

    private Checks() {}

    public static void period(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw invalid("from은 to보다 늦을 수 없습니다");
    }

    public static void page(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) throw invalid("page는 0 이상, size는 1~" + MAX_SIZE + "이어야 합니다");
    }

    public static void result(String result) {
        if (result != null && !"OK".equals(result) && !"NG".equals(result)) throw invalid("result는 OK 또는 NG입니다");
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
