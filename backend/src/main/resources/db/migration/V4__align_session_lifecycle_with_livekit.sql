-- 세션 생명주기를 확정 LiveKit 계약(PREPARING → LIVE → ENDING → NOTE_PENDING)에 맞춘다.
-- 근거: .agents/livekit-backend-guide.md §4·§5.
--
-- expand 단계만 수행한다. NOT NULL 완화와 nullable 컬럼 추가뿐이라 기존 행 backfill이 필요 없고,
-- 이전 버전 애플리케이션(항상 값을 채워 넣던 코드)도 그대로 동작한다.
--
-- 두 시각의 의미가 이 migration에서 바뀐다.
--   sessions.started_at        : "행 생성 시각" → "실제 수업 시작 시각". PREPARING 동안 NULL.
--   session_participants.first_joined_at : "API 입장 시각" → "LiveKit 첫 연결 성공 시각".
--                                          API join만 하고 미디어에 접속하지 않은 사용자는 NULL로 남아
--                                          출석·사후 자료 접근 자격과 집계 분모에서 제외된다.
-- 기존 행의 값은 위 의미로 소급 해석해도 무해하다(이미 LIVE로 시작했고 실제 입장도 했던 행들이다).

ALTER TABLE `sessions`
    MODIFY COLUMN `started_at` DATETIME(6) NULL
        COMMENT '실제 수업 시작 시각. PREPARING 동안 NULL이며 시작 성공 시 기록한다',
    MODIFY COLUMN `status` VARCHAR(30) NOT NULL
        COMMENT '미팅 상태, PREPARING, LIVE, ENDING, NOTE_PENDING 또는 ENDED',
    ADD COLUMN `end_reason` VARCHAR(30) NULL
        COMMENT '종료 사유, INSTRUCTOR_REQUEST, MAX_DURATION_REACHED 또는 INSTRUCTOR_ABSENT' AFTER `ended_at`;

ALTER TABLE `session_participants`
    MODIFY COLUMN `first_joined_at` DATETIME(6) NULL
        COMMENT 'LiveKit 첫 연결 성공 시각. API 입장만 한 상태에서는 NULL이다',
    ADD COLUMN `last_joined_at` DATETIME(6) NULL
        COMMENT '가장 최근 LiveKit 연결 성공 시각(재접속 포함)' AFTER `first_joined_at`,
    ADD COLUMN `last_left_at` DATETIME(6) NULL
        COMMENT '가장 최근 LiveKit 이탈 시각' AFTER `last_joined_at`;

ALTER TABLE `session_status_changes`
    MODIFY COLUMN `from_status` VARCHAR(30) NULL
        COMMENT '변경 전 미팅 상태. 최초 생성 전이에서는 NULL',
    MODIFY COLUMN `to_status` VARCHAR(30) NOT NULL
        COMMENT '변경 후 미팅 상태, PREPARING, LIVE, ENDING, NOTE_PENDING 또는 ENDED';

-- 시작되지 않은 채 방치된 PREPARING 세션을 정리하는 배치가 (status, created_at)으로 조회한다.
CREATE INDEX `IX_SESSIONS_STATUS_CREATED_AT` ON `sessions` (`status`, `created_at`);
