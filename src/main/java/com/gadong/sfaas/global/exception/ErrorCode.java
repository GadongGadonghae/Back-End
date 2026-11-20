
package com.gadong.sfaas.global.exception;

import org.springframework.http.HttpStatus;

/** 오류 코드. API 명세서 v2 공통 응답 형식의 code 값이에요. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소가 없습니다"),
    LINE_NOT_FOUND(HttpStatus.NOT_FOUND, "라인을 찾을 수 없습니다"),
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "설비를 찾을 수 없습니다"),
    THRESHOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "알람 기준치를 찾을 수 없습니다"),
    ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "알람을 찾을 수 없습니다"),
    DOWNTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "비가동 이력을 찾을 수 없습니다"),
    REASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "재배정 보고서를 찾을 수 없습니다"),
    PLANNED_STOP_NOT_FOUND(HttpStatus.NOT_FOUND, "계획정지를 찾을 수 없습니다"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다"),
    PLANNED_STOP_CONFLICT(HttpStatus.CONFLICT, "이미 진행 중인 계획정지가 있습니다"),

    // C-03: 사용자 등록 시 로그인 ID 중복
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 로그인 ID입니다"),

    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다"),
    // 11/20 계정 권한: 로그인은 했지만 이 기능을 쓸 권한이 없음 (auth.Roles)
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 기능을 쓸 권한이 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
