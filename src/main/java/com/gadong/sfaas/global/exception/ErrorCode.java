package com.gadong.sfaas.global.exception;

import org.springframework.http.HttpStatus;

/** 오류 코드. API 명세서 v2 공통 응답 형식의 code 값이에요. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소가 없습니다"),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "라인을 찾을 수 없습니다"),
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "설비를 찾을 수 없습니다"),
    // 아래 코드는 명세서에 없는 코드예요. EQUIPMENT_NOT_FOUND와 같은 이름 규칙으로 추가했어요
    THRESHOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "알람 기준치를 찾을 수 없습니다"),
    ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "알람을 찾을 수 없습니다"),
    DOWNTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "비가동 이력을 찾을 수 없습니다"),
    REASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "재배정 보고서를 찾을 수 없습니다"),
    PLANNED_STOP_NOT_FOUND(HttpStatus.NOT_FOUND, "계획정지를 찾을 수 없습니다"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다"),
    PLANNED_STOP_CONFLICT(HttpStatus.CONFLICT, "이미 진행 중인 계획정지가 있습니다"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다"),
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
