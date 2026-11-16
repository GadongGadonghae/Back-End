package com.gadong.sfaas.master.dto;

/** C-04 라인 목록 항목. currentPaintId는 도장 경로 전환(M-09) 구현 전까지 기본 도장과 같아요. */
public record LineResponse(String lineId, String defaultPaintId, String currentPaintId) {}
