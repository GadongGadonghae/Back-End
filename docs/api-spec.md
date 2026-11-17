# SFaaS API 명세서 v0.2

> 최신 Notion 「SFaaS API 명세서 (v0.2)」(2026-11-16 오후 수정 반영)를 Markdown으로 옮긴 파일입니다. 작성: 2026-11-13 · 나연 최 / 수정: 2026-11-16. 명세에 없는 내용은 추가하거나 추측하지 않았고, 적혀 있지 않은 항목(자료형, 필수 여부, 엔드포인트별 오류 코드, 엔드포인트별 인증 여부)은 "명세에 없음"으로 표시했습니다.

## 🔄 v0.2 변경 요약 (11/16)

11/16 API↔DB 대조 회의 결정(DB 담당 의견대로 확정)을 반영했습니다.

| 구분 | v0.1 | v0.2 |
| --- | --- | --- |
| 저장 단위 | `equipment_metric`에 5초 평균만 저장 | 사이클(제품) 1건 = 1행, 공정별 4개 테이블(`slitting_data`, `press_data`, `welding_data`, `painting_data`)에 전부 저장. 5초 평균은 조회·집계 때만 계산 |
| 공정 불량 | 검사 불합격 + 직전 알람이면 `NG_PROCESS` | 공정마다 `result`(OK/NG) 판정, NG면 그 공정에서 폐기 |
| 검사 판정 | `OK` · `OK_RECHECK` · `NG_PROCESS` · `NG_VISION` | 비전 `OK` / `NG`만. `OK_RECHECK`는 KAMP 6번에 재검 정보가 있을 때만 |
| 비가동 코드 | 영문 코드 | 영문 코드 유지. 관리값 이탈 알람은 `UP_BREAKDOWN`에 포함 |
| 추가 | - | 생산 재배정 API(R-01~03), 계획정지 일정 API(S-01~02), `alarm` 테이블 명시, users 컬럼(email, memo, lastLoginAt) |
| P2 | - | 도장 경로 전환(M-09, Q-03, M-08 `paintId`)은 중간발표 이후 |
| 시간대 | +09:00 | 백엔드 `Asia/Seoul` 고정 |
| **오후 수정** | M-10 응답에 `equipmentId` 없음, 상태값 DC 없음, 조치 담당자를 이름(`actionBy`)으로 받음 | M-10에 `equipmentId`를 추가하고 `equipmentId?` 쿼리를 받음(기준치가 설비별). 상태값에 DC(통신 끊김)를 5번째로 추가(R/S/I/D/DC, 우선순위 D > DC > S > I > R). U-08·U-09 조치 담당자는 이름 대신 번호(`actionUserId`)로 받고, 응답에 이름(`actionUserName`)을 붙임(동명이인 대비, DB는 `action_user_id`로 이미 저장) |

**❓ 미정 (임의로 정하지 않음):** 설비 ID 체계(`A-PR1` vs `PRESS-A-1`), 측정 항목 이름(`pressure1` vs `pressure_left`), 시간당 생산 능력 값, 노사협의(`UP_LABOR`) RFP 확인, DC(통신 끊김)의 가동률 처리·사유 코드. 문서 예시는 임시로 v0.1 이름을 그대로 씁니다.

## 개요

화면 2개(모니터링 · 가동률)와 공통 화면(로그인 · 사용자 관리)에 필요한 REST API 41개, MQTT 토픽 3개, WebSocket(STOMP) 채널 4개를 정리한 개발 전 초안입니다. 개발이 시작되면 springdoc-openapi로 Swagger UI(`/swagger-ui.html`)에 자동 문서화하고, MQTT·WebSocket은 Swagger에 나오지 않으므로 이 문서가 계속 기준입니다. (11/13 발표 피드백 반영: Node-RED는 수집만, RSID·알람·비가동 사유 판정은 Spring Boot)

- Base URL: `http://localhost:8080/api/v1`
- 인증: Spring Security + JWT. 로그인 후 `Authorization: Bearer <token>` 헤더. (C-01 로그인 외 엔드포인트별 인증 여부는 명세에 없음)
- 시간: ISO-8601 KST(`2026-11-18T09:30:00+09:00`). 백엔드는 `Asia/Seoul` 고정, DB는 시간대 없는 `TIMESTAMP`에 KST로 저장. 기간 조회는 `from`, `to` 쿼리 파라미터
- 명명: URL은 소문자·복수형 명사(`/equipments`), JSON 필드는 camelCase
- 담당: BE1 = 설비·생산 데이터 백엔드, BE2 = 인증·사용자 백엔드, FE = Vue 프론트, IoT = Node-RED·가상 설비 (실제 이름은 팀 합의 후 채움)
- 우선순위: P1 = 중간발표(11/18)까지 필수, P2 = 기본 기능 이후, P3 = 고도화
- 라인 선택: 화면 상단 라인 선택(전체 / A~F)은 `lineId` 쿼리로 보내고, 생략하면 전체. 오늘 생산(M-07), 통합 가동률 OEE(U-01), 최근 알림(M-04), 공정 상세(M-12)가 같은 `lineId`로 함께 바뀜
- 성공 응답은 데이터를 그대로(래퍼 없음), 목록은 `page`, `size`로 나눔

### 공통 오류 형식

```json
{
  "status": 404,
  "code": "EQUIPMENT_NOT_FOUND",
  "message": "설비 X-PR9를 찾을 수 없습니다",
  "timestamp": "2026-11-18T09:30:00+09:00"
}
```

엔드포인트별 오류 코드 목록은 명세에 없음(위 예시의 `EQUIPMENT_NOT_FOUND`만 기재됨).

## 공통 규칙

### 설비 ID · 공정 코드

설비 ID는 `라인-공정번호` 형식이고, 공용 설비는 라인 없이 적습니다. 총 39대(설비목록_v1.csv 기준).

> ❓ **미정:** 설비 ID는 `설비목록_v1.csv`(`A-PR1`)와 DB seed(`PRESS-A-1`) 두 가지가 있어 하나로 정해야 합니다. 공정 코드(`PRESS` vs `SLIT`·`WELD` 등)도 함께 정합니다. 그 전까지 예시는 csv 방식을 임시로 씁니다.

| 구분 | 규칙 | 예시 | 대수 |
| --- | --- | --- | --- |
| 슬리터 | `{라인}-SL` | A-SL | 6 |
| 프레스 | `{라인}-PR{1~4}` | A-PR1 | 24 |
| 용접 로봇 | `{라인}-WD` | C-WD | 6 |
| 도장 | `PT{1~2}` | PT2 | 2 |
| 비전 검사기 | `VI` | VI | 1 |

- 공정 코드(`process`): `SLITTING` · `PRESS` · `WELDING` · `PAINTING` · `INSPECTION`
- 라인 코드(`lineId`): `A`~`F`

### 설비 상태 (`status`, RSID)

Spring Boot가 MQTT 원시 값으로 판정하고, 여러 신호가 겹치면 **D > DC > S > I > R** 순서로 하나만 표시합니다.

| 코드 | 의미 | 화면 색 (제안) | 가동률 계산 |
| --- | --- | --- | --- |
| R 🟢 | 가동 | 초록 | 가동 시간 |
| S ⚪ | 정지(계획) | 회색 | 계획 가동시간에서 제외 |
| I 🟡 | 대기 | 노랑 | 비계획정지(재공부족·작업지연) |
| D 🔴 | 고장·알람 | 빨강 | 비계획정지(장비고장) |
| DC 🟣 | 통신 끊김 | 보라 (제안) | ❓ 미정 (임시로 비계획정지, 사유 `UP_ETC`) |

### 비가동 사유 코드 (`reasonCode`)

1. 계획정지: `PL_SHIFT`(교대) · `PL_PM`(예방보전) · `PL_CHANGE`(모델 교체) · `PL_SETUP`(슬리팅 코일·나이프 교체)
2. 비계획정지: `UP_BREAKDOWN`(장비고장) · `UP_DELAY`(작업지연) · `UP_SHORTAGE`(재공부족) · `UP_LABOR`(노사협의) · `UP_SAFETY`(안전문제) · `UP_ETC`(기타)

RFP 3번 비가동 분석의 6개 분류를 그대로 따랐습니다(11/16 영문 코드로 확정, DB seed도 이 코드로 맞춤). 관리값 이탈 알람은 별도 코드 없이 `UP_BREAKDOWN`에 포함합니다(분리 여부는 확인 사항으로 보류). `UP_LABOR`(노사협의)는 RFP 원문 확인 전까지 미정입니다. 사유도 Spring Boot가 자동으로 붙이고, 틀린 것만 사람이 고칩니다(U-09).

### 설비 상태 공통 필드 (M-01 · M-12 · W-01 동일)

`equipmentId`, `lineId`, `process`, `status`(R/S/I/D/DC), `since`, `reasonCode`(R이면 null), `lastValues`. 설비 상태를 내려주는 곳은 모두 이 필드를 같은 이름으로 쓰고, 같은 설비는 어느 API로 봐도 같은 값이 나옵니다. 판정 기준도 하나입니다(위 RSID 표와 우선순위 D > DC > S > I > R). 시각은 항상 ISO-8601 KST.

### 공정 판정 (`result`, 각 공정 데이터)

슬리팅·프레스·용접·도장은 사이클(제품) 1건마다 `OK` / `NG`를 판정합니다. NG면 그 공정에서 바로 폐기하고 검사기로 가지 않습니다. 공정 불량 수 = 공정별 테이블의 `result = NG` 행 수.

### 비전 판정 (`result`, 출고 검사)

`OK`(양품) · `NG`(비전 불량). 재검 양품 `OK_RECHECK`는 KAMP 6번 데이터에 재검 정보가 실제로 있을 때만 추가합니다(미정).

최종 양품 = 비전 `OK` (재검을 쓰면 + `OK_RECHECK`). 생산 수 = 검사 수 + 공정 불량 수.

## 모니터링 화면 API (Spring Boot → Vue)

처음 화면을 열 때는 REST로 현재 상태를 한 번에 받고, 그 뒤 변화는 WebSocket으로 받습니다. 공정 상세 화면은 M-12(설비 목록·상태), M-07(lineId 지정 시 공정별 생산량), M-13(재공품), U-03(공정별 가동률·OEE)을 함께 불러 채웁니다.

### 공통 (로그인 · 사용자 · 기준정보)

| ID | 기능 | 메서드 | URL | 요청 | 응답 예시 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-01 | 로그인 | POST | `/auth/login` | body `{loginId, password}` | `{accessToken, userName, role}` | BE2 | P1 |
| C-02 | 사용자 목록 | GET | `/users` | `page`, `size` | `[{loginId, userName, role, email, memo, lastLoginAt}]` | BE2 | P2 |
| C-03 | 사용자 등록 | POST | `/users` | body `{loginId, password, userName, role, email, memo}` | `201 {loginId}` | BE2 | P2 |
| C-04 | 라인 목록 | GET | `/lines` | - | `[{lineId:"A", defaultPaintId:"PT1", currentPaintId:"PT1"}]` (P1에서는 `currentPaintId` = 기본값 고정, 경로 전환은 P2) | BE1 | P1 |
| C-05 | 설비 목록 | GET | `/equipments` | `lineId?`, `process?` | `[{equipmentId:"A-PR1", name, lineId, process, dataSource}]` | BE1 | P1 |

### 모니터링

| ID | 기능 | 메서드 | URL | 요청 | 응답 예시 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M-01 | 전체 설비 현재 상태 (흐름도용) | GET | `/monitoring/status` | `lineId?` | `[{equipmentId:"A-PR1", lineId, process, status:"R", since, reasonCode, lastValues:{...}}]` 39건 | BE1 | P1 |
| M-02 | 라인별 상태 요약 | GET | `/monitoring/lines/summary` | - | `[{lineId:"A", R:5, S:0, I:1, D:0, DC:0, worstStatus:"I"}]` | BE1 | P1 |
| M-03 | 설비 공정 값 추이 (관리값 추이 차트, 상·하한선 포함) | GET | `/equipments/{equipmentId}/metrics` | `from`, `to`, `metric?`, `interval=raw\|5s` (기본 raw = 사이클 값 그대로, 5s = 조회 때 5초 평균 계산) | `{equipmentId, metrics:[{metric, unit, limitLow, limitHigh, points:[{ts, value, result}]}]}` | BE1 | P1 |
| M-04 | 현재 활성 알람 (`alarm` 테이블) | GET | `/alarms/active` | `lineId?` | `[{alarmId, equipmentId, level:"CRITICAL", message, occurredAt}]` | BE1 | P1 |
| M-05 | 알람 이력 | GET | `/alarms` | `from`, `to`, `equipmentId?`, `page`, `size` | `{content:[...], totalElements}` | BE1 | P1 |
| M-06 | 알람 확인 처리 | PATCH | `/alarms/{alarmId}/ack` | body `{ackBy}` | `{alarmId, ackedAt}` | BE1 | P2 |
| M-07 | 출고 양품/불량 카운트 | GET | `/production/counts` | `date`(기본 오늘), `lineId?`, `groupBy=line` (`paint`는 P2) | `{total:1200, processNg:30, inspected:1170, ngVision:28, ok:1142, byGroup:[...], byProcess:[...]}` | BE1 | P1 |
| M-08 | 비전 검사 결과 목록 | GET | `/inspections` | `from`, `to`, `lineId?`, `paintId?`(P2), `result?`(OK/NG), `page`, `size` | `{content:[{inspectionId, lineId:"C", paintId:"PT1", result:"OK", inspectedAt}]}` | BE1 | P2 |
| M-09 | 도장 경로 전환 (⏸️ 중간발표 이후) | PUT | `/lines/{lineId}/paint-route` | body `{paintId:"PT2", reason}` | `{lineId, currentPaintId:"PT2", changedAt}` | BE1 | P2 |
| M-10 | 알람 기준치 조회 (설비별 기준치) | GET | `/alarms/thresholds` | `process?`, `equipmentId?` | `[{id, equipmentId:"C-WD", process:"WELDING", metric:"current", low, high}]` | BE1 | P1 |
| M-11 | 알람 기준치 수정 | PUT | `/alarms/thresholds/{id}` | body `{low, high}` | `{id, low, high, updatedAt}` | BE1 | P1 |
| M-12 | 공정 상세: 공정별 설비 목록·상태·원인 | GET | `/monitoring/processes/{process}/equipments` | `lineId?` (생략 시 전체) | `[{equipmentId:"A-PR1", lineId:"A", process:"PRESS", status:"D", reasonCode:"UP_BREAKDOWN", since, lastValues:{...}}]` | BE1 | P1 |
| M-13 | 공정별 재공품(WIP) 수량 (앞 공정 OK 행 수 − 다음 공정 행 수) | GET | `/production/wip` | `lineId?` (생략 시 전체) | `[{process:"PRESS", nextProcess:"WELDING", wipQty:42}]` | BE1 | P1 |

**M-01 응답 예시 (1건)**

```json
{ "equipmentId": "A-PR1", "lineId": "A", "process": "PRESS", "status": "D", "since": "2026-11-18T09:12:04+09:00", "reasonCode": "UP_BREAKDOWN", "lastValues": { "pressure1": 287.1, "pressure2": 271.0, "pressTime": 551 } }
```

해설: A라인 프레스 1호기(`A-PR1`)가 지금 고장(D) 상태이고 09:12:04부터 그 상태, 사유는 장비고장(`UP_BREAKDOWN`)입니다. `lastValues`는 마지막으로 받은 공정 값으로, 압력 1이 287.1bar, 압력 2가 271.0bar, 프레스 시간이 551(단위 0.01초 = 5.51초)입니다. 압력 1이 알람 상한(285.3bar, DB 초안 값)을 넘어 고장(D)으로 판정된 상태이며 관리값 이탈 알람도 사유는 `UP_BREAKDOWN`입니다. 이 건이 39대분 배열로 와서 공정 흐름도의 설비 색(R·S·I·D·DC)을 칠합니다.

**M-07 응답 예시 (`groupBy=line`)** — `total`(생산) = `inspected`(검사 수) + `processNg`(공정 불량), 최종 양품 `ok` = 검사 수 − 비전 불량. 양품률 = `ok ÷ total`이며 백엔드가 계산해서 내려줍니다. lineId를 지정하면 `byProcess`에 공정별 생산·불량 수가 추가됩니다. (`OK_RECHECK`를 쓰기로 하면 `okRecheck` 필드를 추가합니다.)

```json
{ "date": "2026-11-18", "total": 1200, "processNg": 30, "inspected": 1170, "ngVision": 28, "ok": 1142, "yieldRate": 95.2, "byGroup": [ { "key": "A", "total": 210, "good": 201, "ng": 9 } ], "byProcess": [ { "process": "PRESS", "produced": 1215, "ng": 12 } ] }
```

해설: 오늘 생산된 제품이 1,200개이고 그중 30개는 공정에서 NG로 폐기, 나머지 1,170개가 출고 검사를 받았습니다. 비전 불량 28개라 최종 양품은 1,170 − 28 = 1,142개, 양품률은 1,142 ÷ 1,200 = 95.2%입니다. `byProcess`는 공정별 분해(프레스 1,215개 중 NG 12개), `byGroup`은 라인별 분해(A라인 210개 중 양품 201, 불량 9)입니다.

## 가동률 화면 API (Spring Boot → Vue)

가동률은 백엔드가 상태 이력(RSID 구간)으로 계산해서 내려주고, 프론트는 그리기만 합니다. 1·2·3번 화면(기본·요일별·비가동 분석)을 우리 공장 구조(공장별 → 라인·공정별)로 바꾼 것입니다.

```
가동률 = (계획 가동시간 − 비계획정지 시간) / 계획 가동시간 × 100
```

계획 가동시간 = 조회 기간 − 계획정지(S) 시간(계획정지 일정 S-01 기준). 손실 수량 = 비계획정지 시간 × 시간당 생산 능력. ❓ 시간당 생산 능력(`line.capacity_per_hour`) 값이 미정이라 그 전까지 `lossQty`와 속도가동률(`performance`)은 `null`로 내려갑니다. OEE = 시간가동률(availability) × 속도가동률(performance) × 양품률(quality)이고, 시간가동률은 위 가동률과 같습니다. U-01 응답에는 OEE와 세 요소를 함께 내려줍니다. RFP 기준에 맞춰 1분 이상 정지만 비가동 1건으로 셉니다.

| ID | 기능 | 메서드 | URL | 요청 | 응답 예시 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| U-01 | 공장 전체 가동률 요약 (목표/실적) | GET | `/utilization/summary` | `from`, `to`, `lineId?` | `{target:95.0, actual:91.4, oee:74.0, availability:91.4, performance:88.5, quality:91.5, plannedMin, unplannedStopMin, lossQty}` | BE1 | P1 |
| U-02 | 가동률 추이 | GET | `/utilization/trend` | `from`, `to`, `unit=day\|week\|month`, `lineId?` | `[{period:"2026-11-17", target:95.0, actual:92.1}]` | BE1 | P1 |
| U-03 | 공정별 가동률 | GET | `/utilization/by-process` | `from`, `to`, `lineId?` | `[{process:"PRESS", actual:89.3, oee:74.0, unplannedStopMin:132}]` | BE1 | P1 |
| U-04 | 라인별 가동률 | GET | `/utilization/by-line` | `from`, `to` | `[{lineId:"A", actual:93.0, stopCount:4}]` | BE1 | P1 |
| U-05 | 설비별 가동률 순위 (낮은 순) | GET | `/utilization/by-equipment` | `from`, `to`, `process?`, `limit=10` | `[{equipmentId:"C-WD", actual:78.5}]` | BE1 | P2 |
| U-06 | 요일별 가동률 + 비가동 건수 | GET | `/utilization/by-weekday` | `from`, `to`, `lineId?` | `[{weekday:"MON", actual:96.6, stopCount:108}]` | BE1 | P2 |
| U-07 | 비가동 사유별 집계 (파이·표) | GET | `/downtime/summary` | `from`, `to`, `groupBy=line\|process` | `{totalMin:1616, byReason:[{reasonCode:"UP_BREAKDOWN", minutes:175, ratio:10.8}], byGroup:[...]}` | BE1 | P1 |
| U-08 | 알람·비가동 이력 목록 (조회 조건 + 조치 담당자) | GET | `/downtime/events` | `from`, `to`, `lineId?`, `process?`, `status?`(R/S/I/D/DC), `reasonCode?`, `inProgress?`(진행중만), `actionUserId?`(조치 담당자 번호), `equipmentId?`, `minMinutes=1`, `page`, `size` | `{content:[{eventId, lineId, equipmentId, process, status:"D", reasonCode, startAt, endAt, minutes, inProgress, actionUserId, actionUserName, memo}]}` | BE1 | P1 |
| U-09 | 비가동 사유·조치 담당자·메모 입력/수정 | PATCH | `/downtime/events/{eventId}` | body `{reasonCode, memo, actionUserId}` | `{eventId, reasonCode, actionUserId, actionUserName}` | BE1 | P2 |
| U-10 | 장비 고장 Top N (30분 이상) | GET | `/downtime/top` | `from`, `to`, `minMinutes=30`, `limit=5` | `[{equipmentId:"B-PR3", totalMin:265, count:12}]` | BE1 | P2 |
| U-11 | 양품률 추이 | GET | `/production/yield` | `from`, `to`, `unit=day`, `groupBy=line\|paint?` | `[{period:"2026-11-17", total:1180, good:1121, yieldRate:95.0}]` | BE1 | P1 |
| U-12 | 설비 R/S/I/D/DC 상태 타임라인 | GET | `/equipments/{equipmentId}/status-history` | `from`, `to` | `[{status:"R", startAt, endAt}, {status:"D", startAt, endAt, reasonCode}]` | BE1 | P2 |

**U-07 응답 예시**

```json
{ "from": "2026-11-17", "to": "2026-11-18", "totalMin": 1616, "byReason": [ { "reasonCode": "UP_BREAKDOWN", "minutes": 175, "count": 12, "ratio": 10.8 }, { "reasonCode": "UP_SHORTAGE", "minutes": 235, "count": 20, "ratio": 14.5 } ], "byGroup": [ { "key": "A", "totalMin": 298, "UP_BREAKDOWN": 45 } ] }
```

해설: 조회 기간 비계획정지가 총 1,616분이고, 그중 장비고장은 12번 발생해 175분(전체의 10.8%)입니다. 이 값으로 비가동 파이차트를 그립니다.

## 설비 데이터 · 생산량 조회 API (UI 초안 추가 항목)

공정흐름의 '오늘 생산'을 눌러 들어가는 생산량 조회 페이지(기간별·라인별·계획 대비)와 설비 데이터 조회 페이지에 필요한 API입니다. '계획 대비'의 계획은 일 생산 목표를 G-03으로 입력해 두는 방식으로 가정했습니다. 채팅은 후순위(P3)라 URL만 잡아 둔 것이며, 로그인 권한 분리(`role`, C-01 응답)와 이어지는 기능입니다. 실시간 전달은 `/topic/chat`으로 할 예정입니다.

| ID | 기능 | 메서드 | URL | 요청 | 응답 예시 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E-01 | 설비 데이터 조회 (공정별 테이블의 사이클 단위 행) | GET | `/equipment-data` | `from`, `to`, `lineId?`, `process?`, `equipmentId?`, `result?`(OK/NG), `page`, `size` | `{content:[{ts, equipmentId, process, result:"OK", values:{pressure1:275.1, pressure2:271.3}}], totalElements}` | BE1 | P2 |
| G-01 | 생산량 조회 (기간별·라인별·계획 대비) | GET | `/production/summary` | `from`, `to`, `unit=day\|week\|month`, `lineId?` | `[{period:"2026-11-17", lineId:"A", planQty:1500, actualQty:1240, achievementRate:82.7}]` | BE1 | P2 |
| G-02 | 일 생산 계획(목표) 조회 | GET | `/production/plans` | `from`, `to`, `lineId?` | `[{date:"2026-11-17", lineId:"A", planQty:1500}]` | BE1 | P2 |
| G-03 | 일 생산 계획(목표) 설정 | PUT | `/production/plans/{date}` | body `{lineId, planQty}` | `{date, lineId, planQty}` | BE1 | P2 |
| H-01 | 채팅 메시지 목록 | GET | `/chat/messages` | `page`, `size` | `{content:[{messageId, sender, text, sentAt}]}` | BE2 | P3 |
| H-02 | 채팅 메시지 전송 | POST | `/chat/messages` | body `{text}` | `201 {messageId}` | BE2 | P3 |

G-01 해설: 17일 A라인 계획은 1,500개인데 실제로 1,240개를 만들었고, 달성률은 82.7%입니다.

## 생산 재배정 · 계획정지 일정 API (v0.2 추가)

생산 재배정은 11/13 발표 피드백의 핵심이고 수행계획서 TO-BE 4번입니다. DB의 `reassignment_report`, `reassignment_detail` 테이블을 조회·승인합니다. 흐름은 정지 30분 이상 → 손실 수량 계산 → 여유 라인 추천 → 관리자 승인 → 담당자 알림입니다. 보고서는 Spring Boot가 자동으로 만들고, 손실 수량은 시간당 생산 능력이 정해져야 계산됩니다(❓ 미정). 계획정지 일정은 RSID의 S 판정과 계획 가동시간 계산에 쓰입니다.

| ID | 기능 | 메서드 | URL | 요청 | 응답 예시 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-01 | 재배정 보고서 목록 | GET | `/reassignments` | `from`, `to`, `status?`(PENDING/APPROVED), `page`, `size` | `{content:[{reportId, sourceLineId:"B", equipmentId, stopMin:42, lossQty, status:"PENDING", createdAt}]}` | BE1 | P1 |
| R-02 | 재배정 보고서 상세 (라인별 추가 배정) | GET | `/reassignments/{reportId}` | - | `{reportId, sourceLineId, lossQty, details:[{targetLineId:"D", addQty}], status, approvedBy, approvedAt}` | BE1 | P1 |
| R-03 | 재배정 관리자 승인 | PATCH | `/reassignments/{reportId}/approve` | body `{approvedBy}` | `{reportId, status:"APPROVED", approvedAt}` | BE1 | P2 |
| S-01 | 계획정지 일정 조회 | GET | `/planned-stops` | `from`, `to`, `lineId?` | `[{planId, lineId:"A", reasonCode:"PL_SHIFT", startAt, endAt}]` | BE1 | P2 |
| S-02 | 계획정지 일정 등록 | POST | `/planned-stops` | body `{lineId, reasonCode, startAt, endAt}` | `201 {planId}` | BE1 | P2 |

- 우선순위는 제안입니다. 재배정 조회(R-01·R-02)는 중간발표에서 보여줄 수 있게 P1로, 승인과 일정 등록은 seed 데이터로 대신할 수 있어 P2로 두었습니다.
- 상태 값(`PENDING`/`APPROVED`)과 계획정지 테이블 이름은 DB v0.3 정의서가 나오면 맞춥니다.

## MQTT 토픽 (Node-RED → Spring Boot, 명령 1개는 반대 방향)

11/13 발표 피드백으로 역할을 나눴습니다. **Node-RED는 수집만** 하고 손대지 않은 원시 값을 publish합니다. **상태(RSID)·알람·비가동 사유·양품 분류 판정은 전부 Spring Boot**가 합니다. Spring Boot는 `sfaas/raw/#`를 구독합니다. 지금은 Node-RED → MQTT(Mosquitto) → Spring Boot 구조입니다.

| ID | 토픽 | 방향 | 발행 시점 | QoS / retain | 저장 테이블 (가칭) | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-01 | `sfaas/raw/{equipmentId}` | Node-RED → BE | 1초마다 (프레스·용접은 1사이클마다) | 0 / 없음 | 공정별 4개: `slitting_data` · `press_data` · `welding_data` · `painting_data` (1건 = 1행, `result` 포함) | IoT → BE | P1 |
| Q-02 | `sfaas/raw/VI` | Node-RED → BE | 제품 1개 검사마다 | 1 / 없음 | `inspection_result` (가칭, DB 정의서 이름으로 맞춤) | IoT → BE | P1 |
| Q-03 | `sfaas/cmd/route/{lineId}` | BE → Node-RED | M-09 경로 전환 호출 시 (⏸️ 중간발표 이후) | 1 / retain | - | BE → IoT | P2 |

이전 초안의 `status`·`alarm` 토픽은 판정을 Spring Boot로 옮기면서 없어졌습니다. 판정 결과는 MQTT를 다시 거치지 않고 DB 저장 후 WebSocket으로 바로 나갑니다.

### 페이로드 예시 (판정 필드 없이 원시 값만)

❓ `values` 안의 측정 항목 이름(`pressure1` vs DB `pressure_left` 등)은 미정입니다. KAMP 원본 컬럼명에 가까운 쪽으로 정할 예정이고, 슬리팅은 PLC 레지스터 맵이 나온 뒤 정합니다.

```json
// Q-01 sfaas/raw/A-PR1  (KAMP 7번 재생)
{ "equipmentId": "A-PR1", "ts": "2026-11-18T09:30:01+09:00", "source": "OPCUA", "values": { "pressure1": 275.1, "pressure2": 271.3, "pressureSum": 546.4, "pressTime": 550 } }
```

해설: A라인 프레스 1호기가 09:30:01에 압력 275.1·271.3bar(합 546.4), 프레스 시간 550(0.01초 단위 = 5.5초)을 보냈습니다. 이 메시지 1건이 `press_data` 1행(제품 1개)으로 저장됩니다. 정상인지 고장인지는 여기서 정하지 않고 Spring Boot가 정합니다.

```json
// Q-01 sfaas/raw/A-SL  (슬리팅 PLC 실데이터, 레지스터 값 그대로)
{ "equipmentId": "A-SL", "ts": "2026-11-18T09:30:01+09:00", "source": "MODBUS", "values": { "runCoil": 1, "alarmCoil": 0, "lineSpeed": 42.5, "tension": 18.2, "lengthCounter": 1530 } }
```

해설: A라인 슬리팅 설비의 PLC 값입니다. `runCoil`이 1이면 돌고 있는 중, `alarmCoil`이 0이면 알람 없음, 라인 속도 42.5, 장력 18.2, 진행 길이 1530입니다.

```json
// Q-01 sfaas/raw/C-WD  (KAMP 5번 재생)
{ "equipmentId": "C-WD", "ts": "2026-11-18T09:31:10+09:00", "source": "OPCUA", "values": { "force": 2.8, "current": 15.3, "voltage": 2.7, "weldTime": 71 } }
```

해설: C라인 용접 로봇이 09:31:10에 전류 15.3kA를 측정했습니다(알람 상한 15.07kA 초과, DB 초안 값). 기준치(M-10) 초과 여부는 Spring Boot가 비교해 알람을 만듭니다.

```json
// Q-02 sfaas/raw/VI  (KAMP 6번 재생, 검사기 출력 그대로)
{ "ts": "2026-11-18T09:32:00+09:00", "productSeq": 123, "lineId": "C", "paintId": "PT1", "visionPass": false }
```

해설: C라인 제품 123번의 비전 검사 결과입니다. 비전 불합격(`visionPass: false`)이라 Spring Boot가 `NG`로 판정합니다. 공정 NG품은 이미 공정에서 폐기돼 검사기까지 오지 않습니다. KAMP 6번에 재검 정보가 있으면 `recheckPass` 필드와 `OK_RECHECK` 판정을 추가합니다(미정). `paintId`는 P1에서는 기본 경로 값입니다.

```json
// Q-03 sfaas/cmd/route/C
{ "lineId": "C", "paintId": "PT2", "requestedBy": "admin", "ts": "2026-11-18T09:35:00+09:00" }
```

해설: 방향이 반대인 명령입니다. 관리자(admin)가 C라인 도장을 PT2 설비로 바꿔 달라고 Spring Boot가 Node-RED에 보내는 메시지입니다.

### Spring Boot 판정 규칙 (초안)

| 판정 | 입력 | 규칙 | 저장 | 화면 알림 |
| --- | --- | --- | --- | --- |
| 상태 RSID | Q-01 원시 값 + 계획정지 일정(`planned_stop` 가칭, S-01) | 계획정지 시간대면 S, 알람 발생 중이거나 슬리터 `alarmCoil=1`이면 D, 값이 10초 이상 안 들어오면 DC(통신 끊김), `runCoil=0`이면 I, 나머지 R. 겹치면 D > DC > S > I > R | `equipment_status_history` | W-01 |
| 알람 | Q-01 원시 값 + 기준치(M-10) | 상한·하한 이탈 시 RAISED, 범위 복귀 시 CLEARED. 발생·해제·확인 시각, 등급, 메시지, 확인자, 당시 원시 값 저장 | `alarm` | W-02 |
| 공정 판정 | Q-01 사이클 값 + 기준치(M-10) | 사이클 1건마다 기준치 안이면 OK, 벗어나면 NG(그 공정에서 폐기) | 공정별 테이블 `result` | W-03 |
| 비가동 사유 | 1분 이상 이어진 S·I·D 구간 | S는 일정의 PL_* 코드, D(장비고장·관리값 이탈 알람)는 UP_BREAKDOWN, I이고 앞 공정이 멈췄으면 UP_SHORTAGE, 나머지 UP_ETC. 틀린 건 U-09로 수정 | `downtime_event` | W-01 |
| 비전 판정 | Q-02 원시 값 | 비전 통과 OK, 불합격 NG (재검 정보가 있으면 재검 통과 OK_RECHECK, 미정) | `inspection_result` | W-03 |

(11/16 변경) Q-01은 사이클(제품) 1건마다 판정하고 **전부 공정별 테이블에 저장**합니다. 행 수로 공정별 생산량·공정 불량·재공품을 세기 때문입니다. 5초 평균은 저장하지 않고 차트·집계 조회 때만 계산합니다(M-03 `interval=5s`). 알람(D)이 나오면 그 순간의 원시 값 묶음을 `alarm` 테이블에도 함께 저장합니다. 테이블 이름은 DB 테이블 정의서 v0.2 기준이고, v0.3이 나오면 다시 맞춥니다.

## WebSocket(STOMP) 메시지 (Spring Boot → Vue)

Vue는 `ws://localhost:8080/ws`에 STOMP로 연결하고 아래 채널을 구독합니다. Spring Boot는 MQTT 원시 값을 받아 판정·저장한 뒤 판정 결과만 화면용으로 보냅니다. 연결이 끊기면 프론트는 REST(M-01, M-04, M-07)로 다시 채우고 재구독합니다.

| ID | 구독 경로 | 보내는 시점 | 화면 | 담당 | 우선 |
| --- | --- | --- | --- | --- | --- |
| W-01 | `/topic/status` | Spring Boot 판정으로 RSID가 바뀔 때 | 모니터링 흐름도 | BE → FE | P1 |
| W-02 | `/topic/alarms` | 알람 발생·해제·확인 시 | 모니터링 알람 팝업·목록 | BE → FE | P1 |
| W-03 | `/topic/production` | 비전 검사 1건마다·공정 NG 발생 시 (누적 카운트 포함) | 모니터링 양품/불량 카운터 | BE → FE | P1 |
| W-04 | `/topic/metrics/{equipmentId}` | 1초마다 (상세 창을 열었을 때만 구독) | 설비 상세 실시간 차트 | BE → FE | P2 |

### 메시지 예시

```json
// W-01 /topic/status
{ "equipmentId": "C-WD", "lineId": "C", "process": "WELDING", "status": "D", "since": "2026-11-18T09:31:10+09:00", "reasonCode": "UP_BREAKDOWN", "lastValues": { "force": 2.8, "current": 15.3, "voltage": 2.7, "weldTime": 71 } }
```

해설: C라인 용접 로봇(C-WD)의 상태가 고장(D)으로 바뀌었다는 알림입니다. 09:31:10부터 그 상태이고 비가동 사유는 장비고장(`UP_BREAKDOWN`)이며, 화면의 설비 색이 빨간색으로 바뀝니다.

```json
// W-02 /topic/alarms
{ "alarmId": 381, "type": "RAISED", "equipmentId": "C-WD", "level": "CRITICAL", "message": "용접 전류 상한 초과", "occurredAt": "2026-11-18T09:31:10+09:00" }
```

해설: 알람 381번이 새로 발생(`RAISED`)했다는 알림입니다. C-WD에서 심각(`CRITICAL`) 단계로 "용접 전류 상한 초과"가 일어났고, 화면에 팝업으로 뜹니다.

```json
// W-03 /topic/production
{ "last": { "lineId": "C", "paintId": "PT1", "result": "OK" }, "today": { "total": 1201, "processNg": 30, "inspected": 1171, "ngVision": 28, "ok": 1143, "yieldRate": 95.2 } }
```

해설: 출고 검사 결과가 하나 나올 때마다 오는 메시지입니다. `last`는 방금 검사한 제품(C라인, 도장 PT1, 양품), `today`는 오늘 누적(생산 1201, 공정 불량 30, 검사 1171, 비전 불량 28, 양품 1143, 양품률 95.2%)입니다. 공정 불량은 공정 NG가 생길 때마다 같은 채널로 갱신합니다.

## 프론트와 합의할 것

- [ ] URL 규칙(`/api/v1`, 복수형)과 래퍼 없는 성공 응답 방식에 동의하는지
- [ ] 목표 가동률(U-01의 `target`)을 고정값(예: 95%)으로 둘지, 설정 API를 따로 둘지
- [ ] 설비 상태 색(R 초록 / S 회색 / I 노랑 / D 빨강 / DC 보라)을 그대로 쓸지
- [ ] 비가동 사유는 Spring Boot가 자동 판정하고 틀린 것만 사람이 수정(U-09)하는 방식이 괜찮은지
- [ ] 실시간 차트 주기 1초가 화면에서 버티는지 (W-04)
- [ ] IoT 담당과 MQTT 페이로드 필드명(특히 슬리팅 PLC 레지스터 맵 확정 후 `values` 키) 맞추기

**발표 피드백에서 나온 추가 검토:** 웹은 레이턴시를 보장할 수 없다는 지적이 있었습니다. 화면은 전부 Vue로 만들기로 확정됐습니다(11/13). 실시간 화면을 WebSocket 푸시(현재 초안)로 둘지, HTTP 주기 조회(예: 2~3초마다 M-01·M-07 호출)로 바꿀지 팀에서 정해야 합니다. 어느 쪽이든 REST API는 그대로 쓰입니다.

합의가 끝나면 이 표를 기준으로 Controller에 `@Operation` 설명을 달아 Swagger로 옮기고, ERD의 테이블 이름을 MQTT 표의 가칭과 맞춥니다.
