# ⚖️ 판정 로직 설계 v1 (2026-11-17)

> 기준: API 명세서 v3.1(`docs/api-spec.md`), DB `schema.sql` v0.3.7, 11/17 사용자 결정.
> 구현 위치: `judge` 패키지. Controller·Node-RED에는 판정을 두지 않아요.

## ✅ 11/17 결정
1. **알람은 경고만.** 관리값을 벗어나도 설비 상태는 R 유지, 그 제품만 NG. (명세서의 "알람 → D, UP_ALARM" 예시는 고쳐야 함)
2. **공정 OK/NG = 알람 조건.** 측정값 중 하나라도 해당 설비 `threshold` 상·하한을 벗어나면 NG + 알람. 용접·도장·슬리팅 기준값은 DB 담당이 정해서 알려 줌 (그 전에는 기준 없는 항목은 비교하지 않음)
3. **재공품은 DB에 데이터가 들어온 처음부터 누적**으로 셈. 프레스 4대는 한 제품이 PR1→PR2→PR3→PR4를 모두 거침. **프레스 사이 재공은 보지 않고, 큰 공정 사이 재공만** 봄
4. **비전 OK/NG는 Node-RED가 장비 판정값을 그대로 저장** (`inspection_result.result` NOT NULL). 백엔드는 비전을 다시 판정하지 않고 집계만 함
5. 새 값 찾기: 1초마다 `result IS NULL` 행 조회 (백엔드 재량)

## 1. 공정 판정 (result) — 1초마다
대상: `slitting_data`, `press_data`, `welding_data`, `painting_data` 중 `result IS NULL` 행 (id 순, 한 번에 최대 500건)

1. 그 행의 `equipment_id`로 `threshold` 목록을 읽음 (설비별, item_name = 컬럼명)
2. 기준이 있는 항목마다 `lower_limit ≤ 값 ≤ upper_limit` 비교 (한쪽만 있으면 그쪽만, 값이 NULL이면 비교 안 함)
3. 하나라도 벗어나면 `result = 'NG'`, 아니면 `'OK'`
4. 알람 처리 (설비 + 항목 단위)
   - 벗어났고 같은 설비·항목에 진행 중 알람(`cleared_at IS NULL`)이 없으면 → `alarm` 새로 저장 (RAISED, level `CRITICAL`, message 예 "프레스 압력 합계 상한 초과", `raw_values`에 그 행의 측정값 전부 JSON)
   - 범위 안으로 돌아왔고 진행 중 알람이 있으면 → `cleared_at` 채움 (CLEARED)
5. 저장 후 W-03(생산), W-02(알람) 푸시
- 알람은 **상태(RSID)를 바꾸지 않음**. 비가동 이력도 만들지 않음

## 2. 재공품 (WIP) — 누적
| 구간 | 재공 = 앞 공정 OK 수 − 다음 공정 투입 수 | 범위 |
|---|---|---|
| COIL → SLITTING | `coil_receipt.product_qty` 합 − 슬리팅 전체 행 수 | 공장 전체 (코일은 라인 구분 없음) |
| SLITTING → PRESS | 라인 슬리터 OK 수 − 라인 프레스 행 수 | 라인별 |
| PRESS → WELDING | 라인 프레스 OK 행 수 − 라인 용접 행 수 | 라인별 |
| WELDING → PAINTING | 이 도장을 쓰는 라인들의 용접 OK 수 − 도장 행 수(`painting_data.line_id` 기준) | 라인별로 세고 도장 설비별로 합침 |
| PAINTING → INSPECTION | 도장 OK 수 − `inspection_result` 행 수 | 도장 설비별 (`paint_equipment_id`) |
- 프레스 4대 중간 재공(PR1→PR2 등)은 보지 않음. 프레스 4대는 앞 구간(SLITTING→PRESS) 재공 하나를 같이 봄
- ✅ (11/17 확인) **DB는 그대로**, `press_data` 1행 = 제품 1개. 프레스 4대가 각자 한 항목씩 맡아 백엔드가 판정함
  | 설비 | 판정 항목 | 값 |
  |---|---|---|
  | {라인}-PR1 | `pressure_left` | 좌 압력 |
  | {라인}-PR2 | `pressure_right` | 우 압력 |
  | {라인}-PR3 | `pressure_diff` (계산값) | 좌 − 우 압력 차 (백엔드 계산, DB 칸 없음) |
  | {라인}-PR4 | `press_time` | 가압 시간 |
  - 앞에서부터 PR1→PR4 순서로 비교, 처음 벗어난 설비에서 NG(폐기)·알람 (알람의 equipment_id = 그 프레스)
  - 기준치는 `threshold`에 설비별로: A-PR1·pressure_left, A-PR2·pressure_right, A-PR3·pressure_diff, A-PR4·press_time
  - `pressure_total`은 판정에 쓰지 않음 (저장만)
  - ❓ Node-RED가 넣는 행의 `equipment_id`: **{라인}-PR4로 통일 제안** (프레스 구간을 마친 시점). IoT 담당과 맞춰야 함
  - ✅ (11/17) DB 담당이 `threshold`에 `pressure_diff` 항목을 넣기로 함

## 3. 상태 판정 (RSID + DC) — 1초마다, 39대 전부
우선순위 **D > DC > S > I > R** (위에서부터 맞는 것 하나)
| 상태 | 조건 | 사유 코드 |
|---|---|---|
| D 고장 | `equipment_signal.fault = 1` | UP_BREAKDOWN |
| DC 통신 끊김 | `now − last_seen_at > 10초` 또는 신호 행 없음 | 없음 (가동으로 계산) |
| S 계획정지 | 그 라인에 진행 중 계획정지(`downtime_event`, PL_ 코드, `ended_at IS NULL`) | 계획정지의 PL_ 코드 (S-02로 등록) |
| I 대기 | 이 설비 앞 구간 재공 ≤ 0 | UP_SHORTAGE |
| R 가동 | 그 외 (알람이 있어도 R) | - |
- 공용 설비(PT1·PT2·VI)는 라인 계획정지 대상이 아님 → S 판정 안 함

## 4. 상태가 바뀔 때 저장
1. `equipment_status_history`: 이전 줄 `ended_at = now`, 새 상태 줄 추가
2. `downtime_event`
   - 새 상태가 **I·D** → 이벤트 새로 만들고 status_history 줄의 `downtime_id`에 연결
   - 새 상태가 **S** → S-02가 만든 이벤트(같은 설비, 진행 중 PL_)를 찾아 연결 (새로 만들지 않음)
   - 이전 상태가 **I·D**였으면 그 이벤트 `ended_at = now` (S 이벤트는 S-03 종료 API가 닫음)
   - R·DC는 이벤트 없음
3. W-01 푸시 (명세서 상태 공통 필드)
- 비가동은 전부 저장, 1분 미만은 조회에서만 제외 (11/16 결정)
- 서버 시작 시: 각 설비의 열린 history 줄을 현재 상태로 읽어 이어감

## 5. 명세서에서 고칠 곳 (사용자 확인 후)
- 상태 표·M-01·W-01 예시의 "D, UP_ALARM" → 알람은 상태를 바꾸지 않음. D는 fault 신호만
- `UP_ALARM` 코드: 비가동 사유로 자동 부여하지 않음 (사람이 U-09로 고를 때만 사용, 또는 삭제)
- 재공품 정의에 "누적, 프레스는 제품 1행 기준" 추가
- 비전 판정 주체: Node-RED가 장비 판정값 저장
