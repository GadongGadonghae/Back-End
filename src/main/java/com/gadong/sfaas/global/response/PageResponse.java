package com.gadong.sfaas.global.response;

import java.util.List;

/** 목록 조회 공통 응답: { content, totalElements }. 성공 응답은 래퍼 없이 데이터를 그대로 내려요. */
public record PageResponse<T>(List<T> content, long totalElements) {

    public static <T> PageResponse<T> of(List<T> content) {
        return new PageResponse<>(content, content.size());
    }
}
