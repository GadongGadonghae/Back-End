package com.gadong.sfaas.global.response;

import java.util.List;

/** 목록 조회 공통 응답: { items, total, page, size }. page는 1부터, total은 조건에 맞는 전체 건수예요. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {}
