[판정로직_설계_v2.md](https://github.com/user-attachments/files/34966731/_._v2.md)
# ⚖️ 판정 로직 설계 v2 (2026-11-17 저녁)

> 기준: API 명세서 v3(레포 `docs/api-spec.md`), DB `schema.sql` v0.3.7, 11/17 사용자 결정.
> v2 변경 (v1 대비): 제품 추적(product_id), 프레스 INSERT 후 UPDATE, 설비마다 판정, 끊긴 제품 NG. 데이터 흐름 예시는 `제품추적_데이터흐름_v1.md`
> 구현 위치: `judge` 패키지. Controller·Node-RED에는 판정을 두지 않아요.

## ✅ 11/17 결정
1. **알람은 경고만.** 관리값을 벗어나도 설비 상태는 R 유지, 그 제품만 NG. (명세서의 "알람 → D, UP_ALARM" 예시는 고쳐야 함)
2. **공정 OK/NG = 알람 조건.** 측정값 중 하나라도 해당 설비 `threshold` 상·하한을 벗어나면 NG + 알람. 용접·도장·슬리팅 기준값은 DB 담당이 정해서 알려 줌 (그 전에는 기준 없는 항목은 비교하지 않음)
3. **재공품은 DB에 데이터가 들어온 처음부터 누적**으로 셈. 프레스 4대는 한 제품이 PR1→PR2→PR3→PR4를 모두 거침. **프레스 사이 재공은 보지 않고, 큰 공정 사이 재공만** 봄
4. **비전 OK/NG는 Node-RED가 장비 판정값을 그대로 저장** (`inspection_result.result` NOT NULL). 백엔드는 비전을 다시 판정하지 않고 집계만 함
5. 새 값 찾기: 1초마다 `result IS NULL` 행 조회 (백엔드 재량)

## ✅ 11/17 저녁 결정 (v2)
6. **제품 추적:** 슬리팅 투입 때 `product_id` 생성 (형식 예 `A-20261117-0001`). 모든 공정 데이터에 `product_id`가 붙음. 비전까지 OK면 **최종 양품**
7. **프레스는 제품 1행에 값이 붙음:** PR1 통과 시 INSERT(pressure_left만), PR2~PR4 통과 시 같은 행 UPDATE. 설비 하나에 값 하나
8. **설비마다 판정:** 값이 붙을 때마다 백엔드가 판정, 벗어나면 그 자리에서 NG → 다음 설비·공정으로 안 감
9-1. **도장은 제품 단위 (11/17):** 제품이 담긴 시점의 용액 상태를 `painting_data` 1행에 `product_id`와 함께 저장. 용액 상태가 기준치를 벗어나면 그 제품 NG
9. **끊긴 제품은 그 공정에서 NG:** 제품 앞 설비가 D 또는 DC가 되면 그 제품 NG. S(계획정지)는 NG 아님 (가정, 확인 필요)
- ❓ 미정 (DB·IoT 담당): 제품 번호를 누가 만드는지, 프레스 행의 `equipment_id`·`measured_at` 갱신 여부

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

### 1-1. 프레스 단계별 판정 (v2)
`press_data` 1행 = 제품 1개. 프레스 4대가 각자 값 하나씩 붙임
| 설비 | 붙는 칸 | 값 |
|---|---|---|
| {라인}-PR1 | `pressure_left` | 좌 압력 (이 시점에 행 INSERT) |
| {라인}-PR2 | `pressure_right` | 우 압력 (UPDATE) |
| {라인}-PR3 | `pressure_diff` | 좌·우 압력 차 (UPDATE, DB 칸 추가 요청) |
| {라인}-PR4 | `press_time` | 가압 시간 (UPDATE) |

1. 1초마다 `result IS NULL`인 프레스 행을 읽음
2. NULL이 아닌 값을 PR1→PR4 순서로 그 설비 기준치(`threshold`: A-PR1·pressure_left, A-PR2·pressure_right, A-PR3·pressure_diff, A-PR4·press_time)와 비교
3. 하나라도 벗어나면 즉시 `result = 'NG'` + 알람 (알람 equipment_id = 그 프레스). 그 제품은 다음 프레스로 안 감
4. 네 값이 다 차고 모두 범위 안이면 `result = 'OK'`
5. 값이 덜 찼고 이탈도 없으면 그대로 둠 (다음 값을 기다림)
- 판정한 칸을 따로 기록하지 않음. 이미 OK인 값은 다시 비교해도 OK
- 1초 폴링, 설비 간 CT 17초 → 다음 설비 도착 전에 판정 끝남
- Node-RED는 `UPDATE ... WHERE product_id = ? AND result IS NULL`로 갱신 → NG 행은 더 안 바뀜
- `pressure_total`은 판정에 쓰지 않음 (저장만)
- **설비별 집계는 칸 기준 (v2):** 프레스 행의 `equipment_id`로 세지 않음. 설비 X의 처리 수 = X 칸이 NULL 아닌 행 수, X의 NG 수 = X 칸 값이 기준 밖인 NG 행 수. OEE 품질·성능, M-03 추이, M-01 lastValues 모두 이 기준
- 프레스 공정 생산 수(M-07 byProcess) = `result IS NOT NULL` 행 수 (생산 중 행 제외)

### 1-2. 끊긴 제품 NG (v2)
- 프레스: `result IS NULL`인 행에서 채워진 값 개수로 다음 설비를 정함 (예: 값 2개 → PR3). 그 설비가 **D 또는 DC**가 되면 `result = 'NG'`
- 다른 공정: 다음 공정 설비가 D·DC일 때 앞 공정 OK 제품을 어떻게 볼지는 미정 (중간발표 범위 밖)
- S(계획정지)는 NG 아님 (가정)

### 1-3. 다음 공정 투입 조건
- 다음 공정 INSERT는 앞 공정 `result = 'OK'`일 때만 (Node-RED·가상 설비, IoT 확인 필요)
- 최종 양품 = `inspection_result.result = 'OK'`

## 2. 재공품 (WIP) — 누적, DB v0.3.9 `product_flow` 기준 (11/18)
재공 = 제품의 **마지막 이동 기록이 끝났고**(`ended_at` 있음, 다음 설비 줄 없음) 공정 판정 NG가 아닌 제품 수. 그 설비 뒤에서 기다리는 재공이에요.
처리 중(`started_at` 있고 `ended_at` 없음)은 재공이 아니에요.
| 구간 | 재공 | 범위 |
|---|---|---|
| COIL → SLITTING | `coil_receipt.product_qty` 합 − 슬리팅에 들어간 제품 수 | 공장 전체 (코일은 라인 구분 없음) |
| SLITTING → PRESS | 슬리터에서 나와 PR1을 기다림 | 라인별 |
| PRESS → WELDING | PR4에서 나와 용접을 기다림 | 라인별 |
| WELDING → PAINTING | 용접에서 나와 도장을 기다림 (`paintId`: 지금 그 도장으로 가는 라인들의 합) | 라인별, 도장 설비별 |
| PAINTING → INSPECTION | 도장에서 나와 검사를 기다림 (`product_flow.line_id`로 라인별) | 라인별, 도장 설비별 |
- 프레스 단 사이 재공(PR1→PR2 등)은 M-13에 안 나오고, 상태 판정(I)에서만 써요
- 예전 계산(앞 공정 OK 행 수 − 다음 공정 행 수)은 처리 중인 제품까지 재공으로 셌어요 (worklog 8번)
- 최종 양품률(lineYield) = 비전 OK 제품 수 ÷ 슬리팅 투입 제품 수
## 3. 상태 판정 (RSID + DC) — 1초마다, 39대 전부
우선순위 **D > DC > S > I > R** (위에서부터 맞는 것 하나)
| 상태 | 조건 | 사유 코드 |
|---|---|---|
| D 고장 | `equipment_signal.fault = 1` | UP_BREAKDOWN |
| DC 통신 끊김 | `now − last_seen_at > 10초` 또는 신호 행 없음 | 없음 (가동으로 계산) |
| S 계획정지 | 그 라인에 진행 중 계획정지(`downtime_event`, PL_ 코드, `ended_at IS NULL`) | 계획정지의 PL_ 코드 (S-02로 등록) |
| I 대기 | 처리 중인 제품 없음 + 바로 앞 재공 0 (product_flow, 프레스 PR2~PR4는 앞 단 뒤) | UP_SHORTAGE |
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
- (v2) Q-01 공정 데이터에 `productId` 추가, 프레스 INSERT 후 UPDATE·설비마다 판정, 끊긴 제품 NG, 최종 양품 정의
