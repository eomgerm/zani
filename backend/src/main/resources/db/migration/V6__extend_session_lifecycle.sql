-- 세션 생명주기를 확정 흐름(PREPARING → LIVE → ENDING → NOTE_PENDING → ENDED)에 맞추고,
-- 종료 사유와 실제 LiveKit 입장·이탈 시각을 저장할 자리를 만든다.
--
-- V1~V5 는 이미 dev 에 머지돼 공유 환경에 적용됐다. 적용된 마이그레이션을 고치면 체크섬이
-- 어긋나고 validate-on-migrate 가 켜져 있어 그 환경의 애플리케이션이 기동하지 못한다.
-- 그래서 forward-only 로 V6 을 낸다.

-- 시작 시각을 NULL 허용으로 넓힌다. PREPARING 세션은 아직 시작하지 않았고, 3시간 자동 종료의
-- 기준이 이 값이라 생성 시각으로 채워두면 준비에 쓴 시간만큼 수업 시간이 줄어든다.
-- 기존 행은 모두 이미 시작한 세션이라 값이 그대로 남는다(넓히는 방향이라 되돌릴 필요가 없다).
ALTER TABLE `sessions`
    MODIFY COLUMN `status` VARCHAR(30) NOT NULL COMMENT '미팅 상태. PREPARING, LIVE, ENDING, NOTE_PENDING 또는 ENDED',
    MODIFY COLUMN `started_at` DATETIME(6) NULL COMMENT '실제 수업 시작 시각. 아직 시작하지 않은 PREPARING 세션은 NULL',
    ADD COLUMN `end_reason` VARCHAR(30) NULL COMMENT '종료 사유. INSTRUCTOR_REQUEST, MAX_DURATION_REACHED 또는 INSTRUCTOR_ABSENT' AFTER `ended_at`;

ALTER TABLE `session_status_changes`
    MODIFY COLUMN `from_status` VARCHAR(30) NULL COMMENT '변경 전 미팅 상태. 최초 생성이면 NULL',
    MODIFY COLUMN `to_status` VARCHAR(30) NOT NULL COMMENT '변경 후 미팅 상태';

-- 실제로 LiveKit 방에 들어온 시각과 마지막으로 나간 시각. webhook(participant_joined·participant_left)이 채운다.
--
-- first_joined_at 을 덮어쓰지 않고 컬럼을 따로 두는 이유: 그 값은 "입장 API 를 호출했다"는 뜻이고
-- 이 값은 "실제로 방에 들어왔다"는 뜻이다. 서로 다른 사실이라 출석 확정에는 이쪽만 쓴다.
-- 프리조인 화면만 보고 나간 학생을 출석으로 세지 않으려면 두 값을 구분해야 한다.
ALTER TABLE `session_participants`
    ADD COLUMN `media_first_joined_at` DATETIME(6) NULL COMMENT '실제 LiveKit 방에 처음 들어온 시각. 한 번 심으면 재접속으로 갱신하지 않는다' AFTER `first_joined_at`,
    ADD COLUMN `media_last_left_at` DATETIME(6) NULL COMMENT '마지막으로 LiveKit 방에서 나간 시각. 재접속하면 다시 NULL 이 아니라 그대로 두고 다음 이탈에 갱신한다' AFTER `media_first_joined_at`;
