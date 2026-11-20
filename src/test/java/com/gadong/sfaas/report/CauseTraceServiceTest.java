package com.gadong.sfaas.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.gadong.sfaas.report.CauseTrace.Sentence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 원인 추적 문장 검증과 근거 강도 등급 (DB 없이) */
class CauseTraceServiceTest {

    private static final Map<String, String> SOURCE = Map.of(
            "E1", "C라인 프레스 3호기 장비고장 10:09:16 시작 · 정지 32초",
            "E2", "압력 차(좌-우) 알람 (위험) 압력 차 상한 초과 · 측정 13.4bar · 기준 -1.8~12.5bar · 고장 4분 전 (10:05:02)",
            "C1", "압력 차(좌-우) 이상 (상한 초과) 90점 직접 신호 40/40");

    private static Sentence s(String text, String kind, String... ids) {
        return new Sentence(text, kind, List.of(ids), false, null);
    }

    @Test
    void 근거에_있는_숫자만_쓰면_통과한다() {
        List<Sentence> out = CauseTraceService.verify(List.of(
                s("10:05:02에 압력 차가 13.4bar로 상한 12.5bar를 넘었습니다.", "fact", "E2"),
                s("가장 강한 원인 후보는 압력 차 이상이며 근거 강도는 높음(90점)입니다.", "inference", "C1", "E2")), SOURCE);

        assertThat(out).allMatch(Sentence::verified);
    }

    @Test
    void 근거에_없는_숫자를_쓰면_실패로_표시한다() {
        Sentence out = CauseTraceService.verify(List.of(s("압력 차가 15.2bar까지 올랐습니다.", "fact", "E2")), SOURCE).get(0);

        assertThat(out.verified()).isFalse();
        assertThat(out.issue()).contains("15.2");
    }

    @Test
    void 근거_번호가_없거나_없는_번호면_실패로_표시한다() {
        List<Sentence> out = CauseTraceService.verify(List.of(
                s("유압 계통 이상으로 보입니다.", "inference"),
                s("유압 계통 이상으로 보입니다.", "inference", "E9")), SOURCE);

        assertThat(out.get(0).issue()).isEqualTo("근거 번호 없음");
        assertThat(out.get(1).issue()).contains("E9");
    }

    @Test
    void 설비_ID_안의_숫자와_작은_순서_숫자는_숫자로_보지_않는다() {
        Sentence out = CauseTraceService.verify(List.of(s("C-PR3 설비는 2가지를 먼저 점검하세요.", "action", "E1")), SOURCE).get(0);

        assertThat(out.verified()).isTrue();
        assertThat(out.kind()).isEqualTo("action");
    }

    @Test
    void 근거_강도_등급은_70점_40점에서_나뉜다() {
        assertThat(CauseTraceService.grade(70)).isEqualTo("HIGH");
        assertThat(CauseTraceService.grade(69)).isEqualTo("MEDIUM");
        assertThat(CauseTraceService.grade(40)).isEqualTo("MEDIUM");
        assertThat(CauseTraceService.grade(39)).isEqualTo("LOW");
    }

    @Test
    void 위험도는_가운데에서_한계까지의_비율이다() {
        assertThat(CauseTraceService.riskPercent(285.3, 270.0, 285.3)).isEqualTo(100);
        assertThat(CauseTraceService.riskPercent(277.65, 270.0, 285.3)).isEqualTo(0);
        assertThat(CauseTraceService.riskPercent(3.6, null, 4.5)).isEqualTo(80);
    }
}
