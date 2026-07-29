-- 참여도 수집 계약을 학생 상태 6종 수신에서 검출기 출력 7종 수신으로 넓힌다.
-- 근거: .agents/attention-coaching-context.md §1(검출기 출력 7종), §2.1(DETECTOR_UNAVAILABLE 전송), §6(서버로 보내는 것)
--
-- 확률 네 개는 저장하지 않는다. 원본 영상을 보관하지 않아 모델 개선 데이터로 쌍을 만들 수 없고,
-- 저장하면 학생 개인의 참여도 시계열만 남는다.
--
-- expand 단계다. 새 컬럼을 모두 nullable 로 넣어 티켓 78 시절에 쌓인 행이 있어도 실패하지 않는다.
-- 계약 이전 행을 정리한 뒤 NOT NULL 로 조이는 contract 마이그레이션은 별도로 낸다.

ALTER TABLE `attention_events`
    ADD COLUMN `detector_outcome` VARCHAR(30) NULL COMMENT '검출기 출력 7종. NOT_ENGAGED, BARELY_ENGAGED, ENGAGED, HIGHLY_ENGAGED, UNMEASURABLE, CAMERA_OFF, DETECTOR_UNAVAILABLE' AFTER `session_participant_id`,
    ADD COLUMN `low_engagement` TINYINT(1) NULL COMMENT '브라우저가 확률 합 0.35 기준으로 판단한 저참여 여부. 4단계 출력에만 있다',
    ADD COLUMN `window_started_offset_ms` BIGINT NULL COMMENT '10초 판정 창 시작 시각(ms). 즉시 확정 출력(CAMERA_OFF, DETECTOR_UNAVAILABLE)은 창이 없어 NULL' AFTER `occurred_offset_ms`,
    ADD COLUMN `feature_schema_version` VARCHAR(40) NULL COMMENT '브라우저가 쓴 특징 추출 계약 버전(예: mediapipe_98_v1)',
    ADD COLUMN `engine_version` VARCHAR(40) NULL COMMENT '브라우저가 쓴 추론 엔진·모델 버전',
    ADD COLUMN `client_event_id` VARCHAR(64) NULL COMMENT '클라이언트가 만든 이벤트 식별자. 재시도 멱등의 기준';

-- 4단계 값이 없는 출력(UNMEASURABLE, CAMERA_OFF, DETECTOR_UNAVAILABLE)이 생겨 점수가 없을 수 있다.
ALTER TABLE `attention_events`
    MODIFY COLUMN `attention_score` TINYINT NULL COMMENT '4단계 참여도 1~4. 4단계가 아닌 출력은 NULL';

-- 같은 참가자가 같은 clientEventId 로 재시도해도 행이 하나만 남는다.
-- client_event_id 가 NULL 인 계약 이전 행은 MySQL 유니크 인덱스에서 서로 충돌하지 않는다.
ALTER TABLE `attention_events`
    ADD CONSTRAINT `UK_ATTENTION_EVENTS_CLIENT_EVENT` UNIQUE (`session_id`, `session_participant_id`, `client_event_id`);
