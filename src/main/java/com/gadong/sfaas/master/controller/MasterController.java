package com.gadong.sfaas.master.controller;

import com.gadong.sfaas.master.dto.EquipmentResponse;
import com.gadong.sfaas.master.dto.LineResponse;
import com.gadong.sfaas.master.service.MasterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "기준정보")
@RestController
@RequestMapping("/api")
public class MasterController {

    private final MasterService masterService;

    public MasterController(MasterService masterService) {
        this.masterService = masterService;
    }

    @Operation(summary = "C-04 라인 목록")
    @GetMapping("/lines")
    public List<LineResponse> lines() {
        return masterService.lines();
    }

    @Operation(summary = "C-05 설비 목록")
    @GetMapping("/equipments")
    public List<EquipmentResponse> equipments(@RequestParam(required = false) String lineId,
                                              @RequestParam(required = false) String process) {
        return masterService.equipments(lineId, process);
    }
}
