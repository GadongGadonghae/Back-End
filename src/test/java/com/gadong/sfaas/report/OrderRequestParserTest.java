package com.gadong.sfaas.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 추가 주문 요청 규칙 해석 (AI 없이) */
class OrderRequestParserTest {

    @Test
    void 가동_중_라인_범위와_추가_수량_한_라인_더를_읽는다() {
        OrderRequestParser.Parsed p = OrderRequestParser.rules("지금 A~D 4개 라인만 돌리고 있는데 추가 주문 300개 들어왔어. 오늘 안에 끝내야 하니 한 라인 더 가동해서 재배정해줘");

        assertThat(p.addQty()).isEqualTo(300);
        assertThat(p.runningLines()).containsExactly("A", "B", "C", "D");
        assertThat(p.newLines()).isEmpty();
        assertThat(p.addLineCount()).isEqualTo(1);
        assertThat(p.dueAt()).isEqualTo("24:00");
    }

    @Test
    void 새로_켤_라인_제외_라인_준비_시간_납기_시각을_읽는다() {
        OrderRequestParser.Parsed p = OrderRequestParser.rules("추가 주문 1,200개, 18시까지. F라인 추가로 돌려줘. B라인은 제외하고 준비 시간 45분");

        assertThat(p.addQty()).isEqualTo(1200);
        assertThat(p.newLines()).containsExactly("F");
        assertThat(p.excludedLines()).containsExactly("B");
        assertThat(p.setupMinutes()).isEqualTo(45);
        assertThat(p.dueAt()).isEqualTo("18:00");
    }

    @Test
    void AI가_말에_없는_라인을_지어내면_빼고_놓친_새_라인은_말에서_채운다() {
        String text = "지금 라인 4개 돌아가고 있는데 추가 주문 300개 왔어. D라인 하나 가동시키고 재배정해";
        OrderRequestParser.Parsed rule = OrderRequestParser.rules(text);
        OrderRequestParser.Parsed ai = new OrderRequestParser.Parsed(300, null, java.util.List.of("A", "B", "C", "D"), java.util.List.of(),
                1, java.util.List.of(), null, "AI · test", java.util.List.of());

        OrderRequestParser.Parsed out = OrderRequestParser.checkAgainstText(ai, rule);

        assertThat(rule.newLines()).containsExactly("D");
        assertThat(out.newLines()).containsExactly("D");
        assertThat(out.runningLines()).isEmpty();   // 이름을 말하지 않았으니 DB 상태로 판단
        assertThat(out.addLineCount()).isNull();
        assertThat(out.notes()).anyMatch(n -> n.contains("A,B,C"));
    }

    @Test
    void 여기에만_배정하라는_라인을_읽는다() {
        assertThat(OrderRequestParser.onlyLines("C라인 고장이므로 C라인에서 생산할 양을 모두 E라인에 배정해줘")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("부족한 수량 전부 E라인으로 돌려줘")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("E라인에 전부 맡겨줘")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("E라인에만 배정해줘")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("E라인만 추가로 생산해")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("e라인에 몰아줘")).containsExactly("E");
        assertThat(OrderRequestParser.onlyLines("E, F라인에만 배정해줘")).containsExactly("E", "F");
        assertThat(OrderRequestParser.onlyLines("E라인과 F라인에 모두 배정")).containsExactly("E", "F");
        assertThat(OrderRequestParser.onlyLines("C라인 물량을 다 E라인이 맡아줘")).containsExactly("E");
    }

    @Test
    void 빼라_멈추라는_말은_여기에만_배정이_아니다() {
        assertThat(OrderRequestParser.onlyLines("A라인만 제외하고 배정해줘")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("E라인에만 배정하지 말고 나눠줘")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("A라인은 제외하고 F라인 정지, 납기 우선")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("지금 A~D 4개 라인만 돌리고 있는데 추가 주문 300개 들어왔어. 한 라인 더 돌려줘")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("D라인 정지하고 재배정해")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("지금 A·B 라인만 돌리고 있는데 추가 주문 300개 들어왔어. 한 라인 더 가동해줘")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("지금 A라인만 생산하고 있어")).isEmpty();
        assertThat(OrderRequestParser.onlyLines("E라인 가동해서 재배정해줘")).isEmpty();
        assertThat(OrderRequestParser.onlyLines(null)).isEmpty();
    }

    @Test
    void 모두_배정의_라인은_가동_중으로_보지_않는다() {
        OrderRequestParser.Parsed p = OrderRequestParser.rules("추가 주문 100개를 모두 E라인에 배정해줘");
        assertThat(p.runningLines()).isEmpty();
        assertThat(p.addQty()).isEqualTo(100);
    }

    @Test
    void 규칙에_없는_요청은_반영하지_못했다고_알린다() {
        assertThat(AiReportService.unhandled("모두 E라인에 배정해줘. 작업자를 두 명 더 넣어줘")).containsExactly("작업자를 두 명 더 넣어줘");
        assertThat(AiReportService.unhandled("A라인은 제외하고 납기 우선으로")).isEmpty();
    }

    @Test
    void 수량이_없으면_알려_준다() {
        OrderRequestParser.Parsed p = OrderRequestParser.rules("한 라인 더 돌려줘");

        assertThat(p.addQty()).isNull();
        assertThat(p.notes()).anyMatch(n -> n.contains("추가 수량"));
    }
}
