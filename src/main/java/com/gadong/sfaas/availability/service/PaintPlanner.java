package com.gadong.sfaas.availability.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 도장 운영 판단 (11/21): 가동 라인을 도장 몇 대에, 어느 도장으로 보낼지. 계산만 하는 순수 함수 (DB·시각 없음, 단위 테스트 대상).
 *
 * <h3>근거</h3>
 * <ul>
 *   <li>라인 1개가 도장 1대를 쓰는 비율(부하) u = 도장 CT ÷ 라인 박자 CT. 라인은 박자마다 제품 1개를 내보내고, 도장은 1개에 CT초를 써요.
 *       예: 도장 34초 · 라인 141초 → u ≈ 0.24 (라인 4개 = 0.96, 도장 1대가 거의 꽉 참)</li>
 *   <li>도장 1대에 걸 수 있는 부하 상한 ρ = 0.85. 1.0에 가까우면 도착 간격이 조금만 흔들려도 앞에 재공이 쌓이고
 *       (M/M/1 근사로 대기 대수 ≈ 부하/(1−부하): 0.85 → 약 5.7대, 0.95 → 19대), 용접이 막혀 라인이 멈춰요(UP_BLOCKED). 그래서 15%를 여유로 남겨요</li>
 *   <li>켜져 있던 도장을 끄는 쪽(합치기)은 상한을 0.80으로 더 낮게 봐요 (이력 현상). 경계 근처에서 라인 하나가 멈췄다 켜질 때마다
 *       도장을 껐다 켰다 하지 않게요. 끄는 판단이 켜는 판단보다 보수적이에요</li>
 * </ul>
 *
 * <h3>고르는 순서 (모든 배정을 다 따져 봐요: 도장 2대 · 라인 6개면 2^6 = 64가지)</h3>
 * <ol>
 *   <li>넘치는 부하 합 Σ max(0, 부하 − 상한)이 가장 작은 것 → 처리할 수 있는지가 먼저</li>
 *   <li>켜는 도장 수가 적은 것 → 남는 도장은 계획정지 (전력·도료 순환·작업자 절약)</li>
 *   <li>경로를 바꾸는 라인 수가 적은 것 → 바꿀 때마다 이송 경로 전환·첫 제품 확인이 필요해요</li>
 *   <li>기본 경로(A·B·C → 도장 1, D·E·F → 도장 2)와 다른 라인 수가 적은 것 → 나중에 되돌리기 쉬움</li>
 *   <li>가장 바쁜 도장의 부하가 작은 것 → 두 대를 켤 때 고르게</li>
 * </ol>
 * 쓸 수 있는 도장(고장·통신 끊김이 아닌 것)이 하나도 없으면 바꾸지 않고 "도장 불가"로 알려요.
 */
public final class PaintPlanner {
    /** 도장 1대 부하 상한 (켜거나 그대로 둘 때) */
    public static final double RHO = 0.85;
    /** 켜져 있던 도장을 끄고 합칠 때의 부하 상한 (이력 현상) */
    public static final double RHO_MERGE = 0.80;

    private PaintPlanner() {
    }

    /**
     * @param running   지금(또는 결재 뒤) 생산하는 라인
     * @param current   라인 → 지금 도장 (모든 라인)
     * @param defaults  라인 → 기본 도장
     * @param usable    쓸 수 있는 도장 (고장·통신 끊김 제외, 계획정지 중이어도 다시 켤 수 있으면 포함)
     * @param onNow     지금 켜져 있는(계획정지가 아닌) 도장
     * @param load      라인 1개의 도장 부하 u (도장 CT ÷ 라인 CT)
     */
    public static Plan plan(List<String> running, Map<String, String> current, Map<String, String> defaults,
                            List<String> usable, List<String> onNow, double load) {
        List<String> lines = running.stream().sorted().toList();
        List<String> booths = usable.stream().sorted().toList();
        if (booths.isEmpty()) {
            return new Plan(new LinkedHashMap<>(current), List.of(), List.of(), Map.of(), lines.size() * load, true, List.of(), true);
        }
        int n = lines.size(), b = booths.size();
        int[] pick = new int[n];
        int[] best = null;
        double[] bestKey = null;
        long total = (long) Math.pow(b, n);
        for (long code = 0; code < total; code++) {
            long c = code;
            for (int i = 0; i < n; i++) { pick[i] = (int) (c % b); c /= b; }
            double[] loads = new double[b];
            for (int i = 0; i < n; i++) loads[pick[i]] += load;
            double over = 0, max = 0;
            int used = 0;
            for (int k = 0; k < b; k++) {
                if (loads[k] > 0) used++;
                max = Math.max(max, loads[k]);
            }
            // 켜져 있던 도장 중 이 안에서 쓰지 않는 것이 있으면 "합치기" → 더 낮은 상한
            boolean merging = false;
            for (int k = 0; k < b; k++) if (loads[k] == 0 && onNow.contains(booths.get(k)) && n > 0) merging = true;
            double cap = merging ? RHO_MERGE : RHO;
            for (int k = 0; k < b; k++) over += Math.max(0, loads[k] - cap);
            int changes = 0, offDefault = 0;
            for (int i = 0; i < n; i++) {
                String to = booths.get(pick[i]);
                if (!to.equals(current.get(lines.get(i)))) changes++;
                if (!to.equals(defaults.get(lines.get(i)))) offDefault++;
            }
            double[] key = {round(over), used, changes, offDefault, round(max)};
            if (bestKey == null || less(key, bestKey)) { bestKey = key; best = pick.clone(); }
        }

        Map<String, String> routes = new LinkedHashMap<>(current);   // 멈춘 라인은 경로 그대로
        Map<String, Double> loads = new LinkedHashMap<>();
        booths.forEach(x -> loads.put(x, 0.0));
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String line = lines.get(i), to = booths.get(best[i]);
            routes.put(line, to);
            loads.merge(to, load, Double::sum);
            if (!to.equals(current.get(line))) changes.add(new Change(line, current.get(line), to));
        }
        TreeSet<String> on = new TreeSet<>(), off = new TreeSet<>();
        for (String x : booths) (loads.get(x) > 0 ? on : off).add(x);
        double sum = n * load;
        boolean overloaded = bestKey[0] > 0;
        return new Plan(routes, List.copyOf(on), List.copyOf(off), loads, sum, overloaded, changes, false);
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static boolean less(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i];
        return false;
    }

    /** 라인 경로 바꾸기 1건 */
    public record Change(String lineId, String from, String to) {
    }

    /**
     * @param routes     라인 → 제안 도장 (멈춘 라인은 지금 경로 그대로)
     * @param on         켤 도장 · off = 끌(계획정지) 도장. 고장·통신 끊김 도장은 둘 다에 없음
     * @param loads      도장 → 제안 부하
     * @param totalLoad  가동 라인 부하 합
     * @param overloaded 상한을 넘는 도장이 남음 (도장이 병목, 용접 뒤 재공이 쌓여요)
     * @param noBooth    쓸 수 있는 도장이 없음
     */
    public record Plan(Map<String, String> routes, List<String> on, List<String> off, Map<String, Double> loads,
                       double totalLoad, boolean overloaded, List<Change> changes, boolean noBooth) {
    }
}
