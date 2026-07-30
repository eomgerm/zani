-- 종료 사유에 ABANDONED_BEFORE_START 를 더한다.
--
-- 준비 중인 세션은 시작 시각이 없어 최대 수업 시간 기준으로는 만료되지 않는다. 그래서 강사가
-- 만들어 놓고 시작하지 않은 수업을 정리하는 경로가 따로 필요하고, 그 사유가 이 값이다.
--
-- V6 의 주석을 고치지 않고 새 버전을 내는 이유: V6 는 이미 적용됐다. 적용된 마이그레이션의
-- 내용을 바꾸면 체크섬이 어긋나고, validate-on-migrate 가 켜져 있어 그 환경의 애플리케이션이
-- 기동하지 못한다.
--
-- 값은 애플리케이션이 enum 이름으로 저장하므로 컬럼 타입은 그대로다. 주석만 최신으로 맞춘다.

ALTER TABLE `sessions`
    MODIFY COLUMN `end_reason` VARCHAR(30) NULL COMMENT '종료 사유. INSTRUCTOR_REQUEST, MAX_DURATION_REACHED, INSTRUCTOR_ABSENT 또는 ABANDONED_BEFORE_START';
