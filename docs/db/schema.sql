-- =====================================================================
-- 라인 가동 현황 모니터링 시스템 · DB 스키마 (PostgreSQL)
-- 1조 가동가동해 · 작성 2026-11-16 · v0.3.2
--
-- 실행 방법:  psql -U postgres -d sfaas -f schema.sql
-- 이 파일이 DB 구조의 "정답지"예요. 테이블을 바꿀 땐 이 파일을 고치고 GitHub에 올려요.
-- 백엔드 JPA 설정은 spring.jpa.hibernate.ddl-auto=validate 로 맞춰주세요.
--
-- v0.3.2 변경 (11/16 백엔드 피드백 2차)
--   · 설비 표시명을 API 형식으로 (seed.sql): 'A라인 프레스 1호기', '도장 1호기', '비전 검사 1호기'
--   · painting_data.line_id 추가 (공용 도장의 라인별 불량 집계)
--   · downtime_event.stop_group_id 추가 (라인 단위 계획정지 묶음 번호 = API stopId) + stop_group_seq
--   · equipment_status_history.downtime_id 추가 (상태 구간 ↔ 비가동 연결)
--
-- v0.3.1 변경 (11/16 백엔드 피드백): 테이블 이름 3개를 API 명세서에 맞춤
--   inspection → inspection_result, status_log → equipment_status_history, downtime → downtime_event
--
-- v0.3 변경 (11/16 회의 결정)
--   · 설비 ID·공정 코드를 API 명세서 방식으로 (A-PR1, PRESS / WELDING ...)
--   · 비가동 코드를 API 영문 코드로 (UP_BREAKDOWN ...) + 알람 UP_ALARM 분리
--   · 관리값 기준(threshold)을 공정별 → 설비별로
--   · 상태에 DC(통신 끊김) 추가
--   · alarm, paint_route_history 테이블 추가 / 라인별 현재 도장 설비, 검사의 도장 설비 칸 추가
--   · 생산 능력: line.capacity_per_hour 삭제 → process.cycle_time_sec(제품 1개 처리 초)
--   · users에 email, memo, last_login_at 추가
-- =====================================================================

-- 다시 실행해도 되도록 기존 테이블을 지우고 새로 만들어요. (데이터도 같이 지워짐!)
DROP TABLE IF EXISTS inspection, status_log, downtime,   -- v0.3까지의 예전 이름
    alarm, paint_route_history,
    reassignment_detail, reassignment_report, downtime_event, equipment_status_history,
    inspection_result, painting_data, welding_data, press_data, slitting_data, coil_receipt,
    production_target, threshold, users, downtime_code, equipment, line, process CASCADE;
DROP SEQUENCE IF EXISTS stop_group_seq;


-- =====================================================================
-- ① 기준정보 (거의 안 바뀌는 데이터, seed.sql로 미리 입력)
-- =====================================================================

-- 공정: 입고 → 슬리팅 → 프레스 → 용접 → 도장 → 검사
CREATE TABLE process (
    process_id      VARCHAR(12)  PRIMARY KEY,       -- 예: 'PRESS', 'WELDING'
    process_name    VARCHAR(20)  NOT NULL,          -- 예: '프레스'
    seq             SMALLINT     NOT NULL UNIQUE,   -- 흐름 순서 (1~6)
    cycle_time_sec  NUMERIC(6,2) NOT NULL DEFAULT 0 -- 제품 1개 처리 시간(초). 시연용으로 짧게 잡은 값
);

-- 라인: A~F (6개)
-- 라인 생산 능력 = 라인 안에서 가장 느린 공정 기준 (예: 프레스 3초 → 시간당 1,200개)
CREATE TABLE line (
    line_id           VARCHAR(5)  PRIMARY KEY,      -- 'A' ~ 'F'
    line_name         VARCHAR(20) NOT NULL,         -- 'A라인'
    default_paint_id  VARCHAR(20),                  -- 기본 도장 설비 (A·B·C → PT1, D·E·F → PT2)
    current_paint_id  VARCHAR(20)                   -- 지금 쓰는 도장 설비 (경로 전환 시 바뀜)
);

-- 설비: 39대 (슬리팅 6 · 프레스 24 · 용접 6 · 도장 2 · 검사 1)
-- ID 규칙(API 명세서): A-SL / A-PR1~4 / A-WD, 공용: PT1, PT2, VI
CREATE TABLE equipment (
    equipment_id    VARCHAR(20) PRIMARY KEY,        -- 코드용 ID. 예: 'A-PR1'
    equipment_name  VARCHAR(30) NOT NULL UNIQUE,    -- 화면 표시명. 예: '프레스A-1'
    process_id      VARCHAR(12) NOT NULL REFERENCES process(process_id),
    line_id         VARCHAR(5)  REFERENCES line(line_id),  -- 공용 설비(도장·검사)는 NULL
    seq_no          SMALLINT    NOT NULL,           -- 같은 라인·공정 안의 번호 (프레스 1~4)
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE
);

-- 라인 → 도장 설비 연결 (equipment가 만들어진 뒤에 걸어야 해서 따로 추가)
ALTER TABLE line
    ADD CONSTRAINT fk_line_default_paint FOREIGN KEY (default_paint_id) REFERENCES equipment(equipment_id),
    ADD CONSTRAINT fk_line_current_paint FOREIGN KEY (current_paint_id) REFERENCES equipment(equipment_id);

-- 비가동 코드: 설비가 멈춘 "이유" 목록 (API 명세서 영문 코드, RFP 기준 + 알람 분리)
CREATE TABLE downtime_code (
    code         VARCHAR(20) PRIMARY KEY,           -- 'PL_SHIFT', 'UP_BREAKDOWN' ...
    code_name    VARCHAR(30) NOT NULL,              -- '장비고장'
    is_planned   BOOLEAN     NOT NULL,              -- TRUE = 계획정지(손실 아님), FALSE = 비계획정지(손실)
    description  VARCHAR(100)
);

-- 사용자 (로그인 계정). 역할 종류는 추후 결정
CREATE TABLE users (
    user_id        SERIAL       PRIMARY KEY,
    login_id       VARCHAR(30)  NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,           -- 비밀번호 원문 저장 금지! Spring Security BCrypt 결과를 저장
    user_name      VARCHAR(20)  NOT NULL,           -- 조치 담당자 이름은 여기서 가져와요
    role           VARCHAR(20)  NOT NULL DEFAULT 'MANAGER',
    email          VARCHAR(100),
    memo           VARCHAR(200),
    last_login_at  TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

-- 관리값 기준 (SCR-009): 설비마다 관리 항목의 상·하한
-- 처음에는 공정 기준값을 같은 종류 설비 전체에 복사해 두고, 필요한 설비만 수정해요
CREATE TABLE threshold (
    threshold_id  SERIAL      PRIMARY KEY,
    equipment_id  VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    item_name     VARCHAR(30) NOT NULL,             -- 수집 테이블의 컬럼명과 같게. 예: 'pressure_left'
    item_label    VARCHAR(30) NOT NULL,             -- 화면 표시명. 예: '좌 압력'
    unit          VARCHAR(10),
    lower_limit   NUMERIC(12,3),
    upper_limit   NUMERIC(12,3),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_by    INTEGER     REFERENCES users(user_id),
    UNIQUE (equipment_id, item_name)
);


-- =====================================================================
-- ② 계획
-- =====================================================================

-- 생산 목표 (SCR-008): 날짜별 · 라인별 목표 수량
CREATE TABLE production_target (
    target_date  DATE       NOT NULL,
    line_id      VARCHAR(5) NOT NULL REFERENCES line(line_id),
    target_qty   INTEGER    NOT NULL CHECK (target_qty >= 0),
    created_by   INTEGER    REFERENCES users(user_id),
    created_at   TIMESTAMP  NOT NULL DEFAULT now(),
    PRIMARY KEY (target_date, line_id)              -- 같은 날 같은 라인 목표는 1개만
);


-- =====================================================================
-- ③ 수집 데이터 (사이클마다 쌓임)
--    공정마다 측정 항목이 달라서 테이블을 나눴어요.
--    측정값 한 줄 = 제품 1개. 평균 내지 않고 전부 저장해요.
--    result가 NG면 그 제품은 바로 폐기되고 다음 공정으로 가지 않아요 (재검 없음).
--    result는 백엔드가 threshold와 비교해서 채워요.
-- =====================================================================

-- 코일 입고 (설비 없음, 가상 데이터)
CREATE TABLE coil_receipt (
    receipt_id   BIGSERIAL   PRIMARY KEY,
    material_id  VARCHAR(30) NOT NULL,              -- 원자재 제품ID
    received_at  TIMESTAMP   NOT NULL               -- 입고시각
);

-- 슬리팅 (실습 모터·인버터 PLC) ※ PLC 확정 후 컬럼 수정 예정
CREATE TABLE slitting_data (
    id            BIGSERIAL   PRIMARY KEY,
    equipment_id  VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    measured_at   TIMESTAMP   NOT NULL,             -- 생산시각
    torque        NUMERIC(10,3),
    current       NUMERIC(10,3),
    voltage       NUMERIC(10,3),
    result        VARCHAR(2)  CHECK (result IN ('OK', 'NG'))   -- 공정 판정: OK 양품 / NG 불량(폐기)
);

-- 프레스 (KAMP 7번 프레스기)
CREATE TABLE press_data (
    id              BIGSERIAL   PRIMARY KEY,
    equipment_id    VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    measured_at     TIMESTAMP   NOT NULL,
    pressure_left   NUMERIC(10,3),                  -- 좌 압력
    pressure_right  NUMERIC(10,3),                  -- 우 압력
    press_time      NUMERIC(10,3),                  -- 가압 시간 (단위 0.01초)
    pressure_total  NUMERIC(10,3),                  -- 압력 합계
    result          VARCHAR(2)  CHECK (result IN ('OK', 'NG'))   -- 공정 판정: OK 양품 / NG 불량(폐기)
);

-- 용접 (KAMP 5번 스폿용접기)
CREATE TABLE welding_data (
    id            BIGSERIAL   PRIMARY KEY,
    equipment_id  VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    measured_at   TIMESTAMP   NOT NULL,
    press_force   NUMERIC(10,3),                    -- 가압력
    current       NUMERIC(10,3),                    -- 전류
    voltage       NUMERIC(10,3),                    -- 전압
    weld_time     NUMERIC(10,3),                    -- 통전시간
    result        VARCHAR(2)  CHECK (result IN ('OK', 'NG'))   -- 공정 판정: OK 양품 / NG 불량(폐기)
);

-- 도장 (KAMP 표면처리·도금욕 대체)
CREATE TABLE painting_data (
    id            BIGSERIAL   PRIMARY KEY,
    equipment_id  VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    measured_at   TIMESTAMP   NOT NULL,             -- 용액상태 측정시각
    line_id       VARCHAR(5)  REFERENCES line(line_id),  -- 이 제품이 온 라인 (도장은 공용이라 꼭 채워주세요)
    ph            NUMERIC(6,3),
    temperature   NUMERIC(8,3),
    current       NUMERIC(10,3),
    voltage       NUMERIC(10,3),
    result        VARCHAR(2)  CHECK (result IN ('OK', 'NG'))   -- 공정 판정: OK 양품 / NG 불량(폐기)
);

-- 출고 검사 (비전 검사기 1대, 6개 라인 공용). 비전 판정만 해요 (재검 없음)
-- line_id: 이 제품이 어느 라인에서 왔는지. 라인별 생산량은 이 칸으로 셉니다.
CREATE TABLE inspection_result (
    inspection_id      BIGSERIAL   PRIMARY KEY,
    equipment_id       VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    product_id         VARCHAR(30) NOT NULL UNIQUE,  -- 완제품ID
    line_id            VARCHAR(5)  NOT NULL REFERENCES line(line_id),
    paint_equipment_id VARCHAR(20) REFERENCES equipment(equipment_id),  -- 거쳐 온 도장 설비 (PT1/PT2)
    result             VARCHAR(2)  NOT NULL CHECK (result IN ('OK', 'NG')),  -- OK 양품 / NG 비전 불량
    inspected_at       TIMESTAMP   NOT NULL,         -- 생산(검사)시각
    shipped_at         TIMESTAMP                     -- 출고시각 (불량이면 NULL)
);

-- 조회가 잦은 "설비 + 시간" 조합에 인덱스 (책의 색인처럼 찾기를 빠르게)
CREATE INDEX idx_slitting_eq_time ON slitting_data (equipment_id, measured_at);
CREATE INDEX idx_press_eq_time    ON press_data    (equipment_id, measured_at);
CREATE INDEX idx_welding_eq_time  ON welding_data  (equipment_id, measured_at);
CREATE INDEX idx_painting_eq_time ON painting_data (equipment_id, measured_at);
CREATE INDEX idx_painting_line_time ON painting_data (line_id, measured_at);
CREATE INDEX idx_inspection_result_line_time ON inspection_result (line_id, inspected_at);
CREATE INDEX idx_coil_time        ON coil_receipt  (received_at);


-- =====================================================================
-- ④ 이벤트 · 이력 (Spring Boot가 판정해서 기록)
-- =====================================================================

-- 상태 이력: 설비 상태가 바뀔 때마다 한 줄. 끝나지 않은 구간은 ended_at = NULL
-- R 가동 · S 정지(계획) · I 대기 · D 고장/알람 · DC 통신 끊김
-- ※ DC는 설비가 돌고 있을 수 있어서 가동률 계산에서는 "가동(R)"으로 봐요 (11/16 결정)
-- ※ 계획정지(S) 시간 안에서 난 고장은 손실로 세지 않아요 (11/16 결정)
CREATE TABLE equipment_status_history (
    log_id        BIGSERIAL   PRIMARY KEY,
    equipment_id  VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    status        VARCHAR(2)  NOT NULL CHECK (status IN ('R', 'S', 'I', 'D', 'DC')),
    started_at    TIMESTAMP   NOT NULL,
    ended_at      TIMESTAMP,
    downtime_id   BIGINT,                           -- S·I·D 구간이면 해당 비가동 번호 (R·DC는 NULL). FK는 아래에서 연결
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX idx_status_history_eq_time ON equipment_status_history (equipment_id, started_at);

-- 비가동 이력 (SCR-002, 003, 006): 멈춘 구간 + 사유 코드 + 조치
-- 저장은 전부 하고, 비가동 "건수"는 조회할 때 1분 이상만 셉니다 (11/16 결정)
-- 계획정지는 일정표 없이 관리자가 원하는 시점에 시작·종료 (status 'S' + PL_ 코드)
-- 라인 단위로 멈추면 그 라인 설비 6대에 한 줄씩 + 같은 stop_group_id (공용 설비 도장·검사는 제외)
CREATE TABLE downtime_event (
    downtime_id     BIGSERIAL   PRIMARY KEY,
    equipment_id    VARCHAR(20) NOT NULL REFERENCES equipment(equipment_id),
    status          CHAR(1)     NOT NULL CHECK (status IN ('S', 'I', 'D')),
    code            VARCHAR(20) NOT NULL REFERENCES downtime_code(code),
    detail          VARCHAR(200),                   -- 예: '압력 상한 초과'
    started_at      TIMESTAMP   NOT NULL,
    ended_at        TIMESTAMP,                      -- NULL = 진행중
    action_memo     VARCHAR(500),                   -- 조치 메모
    action_user_id  INTEGER     REFERENCES users(user_id),  -- 조치한 담당자 (계획정지는 시작한 관리자)
    action_at       TIMESTAMP,
    stop_group_id   BIGINT,                         -- 라인 단위 계획정지 묶음 번호 (API stopId). 같은 정지면 같은 번호
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX idx_downtime_event_eq_time ON downtime_event (equipment_id, started_at);
CREATE INDEX idx_downtime_event_code    ON downtime_event (code);
CREATE INDEX idx_downtime_event_group   ON downtime_event (stop_group_id);

-- 라인 단위 계획정지 번호 발급용: 라인 정지 1번 = nextval 1번 → 그 라인 설비 6대(슬리팅·프레스 4·용접) 행에 같은 번호
CREATE SEQUENCE stop_group_seq;

-- 상태 이력 → 비가동 연결 (downtime_event가 만들어진 뒤에 걸어야 해서 여기서 추가)
ALTER TABLE equipment_status_history
    ADD CONSTRAINT fk_status_history_downtime FOREIGN KEY (downtime_id) REFERENCES downtime_event(downtime_id);

-- 알람 (M-04~06, W-02): 관리값 상·하한 이탈 → 발생(RAISED), 범위 복귀 → 해제(CLEARED)
CREATE TABLE alarm (
    alarm_id        BIGSERIAL    PRIMARY KEY,
    equipment_id    VARCHAR(20)  NOT NULL REFERENCES equipment(equipment_id),
    item_name       VARCHAR(30),                    -- 벗어난 항목. 예: 'pressure_total' (고장 신호면 NULL)
    measured_value  NUMERIC(12,3),                  -- 그때 측정값
    lower_limit     NUMERIC(12,3),                  -- 그때 기준 (나중에 기준이 바뀌어도 기록 유지)
    upper_limit     NUMERIC(12,3),
    level           VARCHAR(10)  NOT NULL DEFAULT 'CRITICAL' CHECK (level IN ('WARNING', 'CRITICAL')),
    message         VARCHAR(200) NOT NULL,          -- 예: '압력 합계 상한 초과'
    raised_at       TIMESTAMP    NOT NULL,          -- 발생 시각
    cleared_at      TIMESTAMP,                      -- 해제 시각 (NULL = 진행중)
    acked_by        INTEGER      REFERENCES users(user_id),  -- 확인한 사람
    acked_at        TIMESTAMP,
    raw_values      JSONB,                          -- 알람 순간의 원시 값 묶음 (원인 분석용)
    CHECK (cleared_at IS NULL OR cleared_at >= raised_at)
);
CREATE INDEX idx_alarm_eq_time ON alarm (equipment_id, raised_at);
CREATE INDEX idx_alarm_active  ON alarm (raised_at) WHERE cleared_at IS NULL;  -- 활성 알람 조회용

-- 도장 경로 전환 이력 (M-09): 라인이 쓰는 도장 설비를 바꾼 기록
CREATE TABLE paint_route_history (
    history_id     BIGSERIAL    PRIMARY KEY,
    line_id        VARCHAR(5)   NOT NULL REFERENCES line(line_id),
    from_paint_id  VARCHAR(20)  REFERENCES equipment(equipment_id),
    to_paint_id    VARCHAR(20)  NOT NULL REFERENCES equipment(equipment_id),
    reason         VARCHAR(200),
    changed_by     INTEGER      REFERENCES users(user_id),
    changed_at     TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_paint_route_line_time ON paint_route_history (line_id, changed_at);

-- 재배정 계획서 (SCR-007): 정지 30분 이상 → 손실 수량 → 여유 라인 추천
-- ※ 실제 재배정은 하지 않고 계획서 작성까지만 (11/16 결정)
CREATE TABLE reassignment_report (
    report_id     BIGSERIAL   PRIMARY KEY,
    downtime_id   BIGINT      NOT NULL REFERENCES downtime_event(downtime_id),
    loss_qty      INTEGER     NOT NULL,             -- 손실 수량 = 정지 시간 × 라인 시간당 생산 능력
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    approved      BOOLEAN     NOT NULL DEFAULT FALSE,
    approved_by   INTEGER     REFERENCES users(user_id),
    approved_at   TIMESTAMP
);

-- 계획서의 재배정 표: 어느 라인에 몇 개를 더 맡길지 (라인마다 한 줄)
CREATE TABLE reassignment_detail (
    report_id       BIGINT     NOT NULL REFERENCES reassignment_report(report_id) ON DELETE CASCADE,
    line_id         VARCHAR(5) NOT NULL REFERENCES line(line_id),
    spare_capacity  INTEGER    NOT NULL,            -- 여유 능력 = 최대 − 현재 계획
    added_qty       INTEGER    NOT NULL,            -- 추가 배정 수량
    PRIMARY KEY (report_id, line_id)
);
