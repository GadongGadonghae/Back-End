package com.gadong.sfaas.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 도장 운영 판단 경우의 수 (11/21). 부하 u = 34 ÷ 141 ≈ 0.241 (느린 공장 시연 값), 도장 1대 = 라인 3개까지(0.72), 4개면 0.96 → 2대 */
class PaintPlannerTest {
    static final double U = 34.0 / 141.0;
    static final Map<String, String> DEF = defaults();
    static final List<String> BOTH = List.of("PT1", "PT2");

    static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : List.of("A", "B", "C")) m.put(l, "PT1");
        for (String l : List.of("D", "E", "F")) m.put(l, "PT2");
        return m;
    }

    static Map<String, String> routes(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>(DEF);
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void 네_라인이면_도장_두_대_그대로() {
        var p = PaintPlanner.plan(List.of("A", "B", "C", "D"), DEF, DEF, BOTH, BOTH, U);
        assertThat(p.on()).containsExactly("PT1", "PT2");
        assertThat(p.changes()).isEmpty();
        assertThat(p.overloaded()).isFalse();
    }

    @Test
    void C라인_정지로_세_라인이면_D를_PT1로_합치고_PT2_운휴() {
        var p = PaintPlanner.plan(List.of("A", "B", "D"), DEF, DEF, BOTH, BOTH, U);
        assertThat(p.on()).containsExactly("PT1");
        assertThat(p.off()).containsExactly("PT2");
        assertThat(p.changes()).extracting(PaintPlanner.Change::lineId).containsExactly("D");   // 바꾸는 라인이 가장 적은 쪽
        assertThat(p.overloaded()).isFalse();
    }

    @Test
    void ABF_세_라인은_F를_PT1로_옮겨_한_대() {
        var p = PaintPlanner.plan(List.of("A", "B", "F"), DEF, DEF, BOTH, BOTH, U);
        assertThat(p.on()).containsExactly("PT1");
        assertThat(p.routes().get("F")).isEqualTo("PT1");
        assertThat(p.changes()).hasSize(1);
    }

    @Test
    void 합쳐져_있다가_EF가_켜지면_PT2를_다시_켠다() {
        Map<String, String> now = routes("D", "PT1");   // C 정지 뒤 D가 PT1로 옮겨 온 상태, PT2 운휴
        var p = PaintPlanner.plan(List.of("A", "B", "D", "E", "F"), now, DEF, BOTH, List.of("PT1"), U);
        assertThat(p.on()).containsExactly("PT1", "PT2");
        assertThat(p.changes()).isEmpty();   // A·B·D → PT1, E·F → PT2 그대로 (3/2로 고름)
        assertThat(p.loads().get("PT1")).isLessThanOrEqualTo(PaintPlanner.RHO);
    }

    @Test
    void 도장_하나_고장이면_남은_도장으로_다_보내고_넘치면_병목() {
        var p = PaintPlanner.plan(List.of("A", "B", "C", "D", "E", "F"), DEF, DEF, List.of("PT1"), List.of("PT1"), U);
        assertThat(p.routes().values()).containsOnly("PT1");
        assertThat(p.overloaded()).isTrue();
    }

    @Test
    void 도장_둘_다_못_쓰면_바꾸지_않는다() {
        var p = PaintPlanner.plan(List.of("A"), DEF, DEF, List.of(), List.of(), U);
        assertThat(p.noBooth()).isTrue();
        assertThat(p.changes()).isEmpty();
    }

    @Test
    void 가동_라인이_없으면_둘_다_운휴() {
        var p = PaintPlanner.plan(List.of(), DEF, DEF, BOTH, BOTH, U);
        assertThat(p.on()).isEmpty();
        assertThat(p.off()).containsExactly("PT1", "PT2");
    }

    @Test
    void 경계_이력현상_켜진_두_대는_부하_082면_끄지_않는다() {
        double u = 0.41;   // 두 라인 = 0.82: 켤 때 상한 0.85 안이지만 끌 때 상한 0.80을 넘음
        var keep = PaintPlanner.plan(List.of("A", "D"), DEF, DEF, BOTH, BOTH, u);
        assertThat(keep.on()).containsExactly("PT1", "PT2");
        var start = PaintPlanner.plan(List.of("A", "D"), routes("D", "PT1"), DEF, BOTH, List.of("PT1"), u);
        assertThat(start.on()).containsExactly("PT1");   // 이미 한 대로 돌면 0.82는 그대로 한 대
    }
}
