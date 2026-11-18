package com.gadong.sfaas.monitoring.controller;

import com.gadong.sfaas.global.response.PageResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProcessNgResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProductDetailResponse;
import com.gadong.sfaas.monitoring.dto.ProductDtos.ProductResponse;
import com.gadong.sfaas.monitoring.service.ProductTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "제품 추적")
@RestController
@RequestMapping("/api")
public class ProductController {

    private final ProductTraceService service;

    public ProductController(ProductTraceService service) {
        this.service = service;
    }

    @Operation(summary = "P-01 제품 목록",
            description = "투입 시각(slitting_data에 product_id가 처음 생긴 시각) 최신순. finalResult = OK(비전 OK) / NG(공정 NG 또는 비전 NG) / IN_PROGRESS. "
                    + "scrappedEquipmentId는 NG 난 설비이고 프레스는 처음 기준을 벗어난 칸의 설비(예: A-PR2)예요.")
    @GetMapping("/products")
    public PageResponse<ProductResponse> products(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) String lineId,
                                                  @RequestParam(required = false) String result,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return service.products(from, to, lineId, result, page, size);
    }

    @Operation(summary = "P-02 제품 이력",
            description = "steps: SLITTING → PRESS(PR1~PR4 단계 4개) → WELDING → PAINTING → INSPECTION. 값이 없는 이후 단계는 빠져요. "
                    + "프레스 단계 result는 그 칸 값을 그 단 설비 기준과 비교한 값이에요.")
    @GetMapping("/products/{productId}")
    public ProductDetailResponse product(@PathVariable String productId) {
        return service.product(productId);
    }

    @Operation(summary = "P-03 공정 불량 제품 목록",
            description = "4개 공정 테이블의 result = 'NG' 행을 합쳐 최신순. 처음 기준을 벗어난 항목으로 itemName·값·기준·reason을 채우고, "
                    + "벗어난 항목이 없으면 reason = \"기준 이탈 없음 (공정 중단)\"이에요.")
    @GetMapping("/production/process-ng")
    public PageResponse<ProcessNgResponse> processNg(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String lineId,
                                                     @RequestParam(required = false) String process,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return service.processNg(from, to, lineId, process, page, size);
    }
}
