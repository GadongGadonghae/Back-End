package com.gadong.sfaas.master.dto;

/** C-05 설비 목록 항목 */
public record EquipmentResponse(String equipmentId, String name, String lineId, String process) {}
