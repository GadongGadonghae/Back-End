package com.gadong.sfaas.master.dto;

/** C-04 라인 목록 항목. currentPaintId는 도장 경로 전환(M-09)으로 바뀌어요. */
public record LineResponse(String lineId, String defaultPaintId, String currentPaintId) {}
