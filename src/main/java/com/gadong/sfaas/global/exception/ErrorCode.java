package com.gadong.sfaas.global.exception;

import org.springframework.http.HttpStatus;

/** 오류 코드. API 명세서 v2 공통 응답 형식의 code 값이에요. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "라인을 찾을 수 없습니다"),
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "설비를 찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}
