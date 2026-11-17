-- 테스트(H2) 전용: Hibernate가 엔티티로 테이블을 만든 뒤 실행해요. 시퀀스는 엔티티가 없어서 직접 만들어요(schema.sql v0.3.7과 같게).
CREATE SEQUENCE IF NOT EXISTS stop_group_seq START WITH 1;
