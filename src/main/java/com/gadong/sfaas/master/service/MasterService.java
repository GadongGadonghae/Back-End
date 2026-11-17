package com.gadong.sfaas.master.service;

import com.gadong.sfaas.global.exception.BusinessException;
import com.gadong.sfaas.global.exception.ErrorCode;
import com.gadong.sfaas.master.dto.EquipmentResponse;
import com.gadong.sfaas.master.dto.LineResponse;
import com.gadong.sfaas.master.entity.Equipment;
import com.gadong.sfaas.master.repository.EquipmentRepository;
import com.gadong.sfaas.master.repository.LineRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MasterService {

    private final LineRepository lineRepository;
    private final EquipmentRepository equipmentRepository;

    public MasterService(LineRepository lineRepository, EquipmentRepository equipmentRepository) {
        this.lineRepository = lineRepository;
        this.equipmentRepository = equipmentRepository;
    }

    public List<LineResponse> lines() {
        return lineRepository.findAll().stream()
                .sorted(Comparator.comparing(l -> l.getLineId()))
                .map(l -> new LineResponse(l.getLineId(), l.getDefaultPaintId(), l.getCurrentPaintId()))
                .toList();
    }

    public List<EquipmentResponse> equipments(String lineId, String process) {
        requireLine(lineId);
        return equipmentRepository.findAll().stream()
                .filter(e -> Boolean.TRUE.equals(e.getActive()))
                .filter(e -> lineId == null || lineId.equals(e.getLineId()))
                .filter(e -> process == null || process.equalsIgnoreCase(e.getProcessId()))
                .sorted(Comparator.comparing((Equipment e) -> e.getLineId() == null ? "~" : e.getLineId())
                        .thenComparing(Equipment::getProcessId)
                        .thenComparing(e -> e.getSeqNo() == null ? 0 : e.getSeqNo()))
                .map(e -> new EquipmentResponse(e.getEquipmentId(), e.getEquipmentName(), e.getLineId(),
                        e.getProcessId(), dataSource(e)))
                .toList();
    }

    /** lineId 쿼리를 받는 API 공통: 없는 라인이면 LINE_NOT_FOUND. null(전체)은 통과 */
    public void requireLine(String lineId) {
        if (lineId != null && !lineRepository.existsByLineId(lineId)) {
            throw new BusinessException(ErrorCode.LINE_NOT_FOUND, "라인 " + lineId + "를 찾을 수 없습니다");
        }
    }

    public Equipment equipment(String equipmentId) {
        return equipmentRepository.findById(equipmentId).orElseThrow(() ->
                new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND, "설비 " + equipmentId + "를 찾을 수 없습니다"));
    }

    /** 수집 경로: 슬리팅은 PLC(Modbus), 나머지는 Python OPC UA 가상 설비. DB에 컬럼이 없어 공정으로 정해요. */
    private static String dataSource(Equipment e) {
        return "SLITTING".equalsIgnoreCase(e.getProcessId()) ? "MODBUS" : "OPCUA";
    }
}
