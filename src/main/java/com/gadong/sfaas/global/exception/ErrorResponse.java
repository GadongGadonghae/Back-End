package com.gadong.sfaas.global.exception;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 실패 응답: { status, code, message, timestamp(KST) }. 성공은 래퍼 없이 데이터를 그대로 내려요. */
public record ErrorResponse(int status, String code, String message, OffsetDateTime timestamp) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.status().value(), code.name(), message,
                OffsetDateTime.now(ZoneId.of("Asia/Seoul")));
    }
}
