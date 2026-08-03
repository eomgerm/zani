-- Track Egress 파일의 화자·트랙 종류를 영속화한다(S15P11A105-97).
--
-- 왜 필요한가: `recording_files.session_participant_id` 는 V1 부터 있었지만 트랙 파일에는 항상 NULL 이 들어갔다.
-- `RecordingFile.trackFile()` 이 그 자리에 null 을 넘겼고, 채울 값이 egress_ended 시점에 없었기 때문이다. 그래서
-- 사후 전사(S15P11A105-247)가 "이 파일은 누가 말한 것인가" 에 답할 수 없다.
--
-- 값이 사라지는 지점은 recordings 다. track_published 는 participant 와 source 를 알지만, 파일 행을 만드는
-- egress_ended 는 egressId 로 recordings 를 찾아올 뿐이라 그 사이에 정보가 끊긴다. 끊긴 자리를 잇는 것이
-- 이 마이그레이션의 목적이다: Egress 시작 시 recordings 에 적어 두고, 종료 시 그 값으로 recording_files 를 채운다.
--
-- 파일명·디렉터리명으로 역추적하지 않는다. 경로에는 익명 별칭(`student-001`)만 들어 있어 참가자 id 로 되돌릴 수
-- 없고, 별칭 순번은 참가자 집합이 바뀌면 같은 문자열이 다른 사람을 가리킬 수 있다.
--
-- 세 컬럼 모두 NULL 허용이다. 이미 적용된 환경에 트랙 파일 행이 있고, 그 행들의 화자를 지금 복원할 방법이 없다.
-- 신규 Track Egress 에 대해서는 도메인이 필수값으로 검증한다(DB 가 아니라 코드가 막는다).
ALTER TABLE `recordings`
    ADD COLUMN `session_participant_id` BIGINT       NULL COMMENT '이 Egress 가 녹화하는 트랙의 발행자 세션 참여자 ID. 종료 시 recording_files 로 옮긴다' AFTER `livekit_egress_id`,
    ADD COLUMN `track_source`           VARCHAR(30)  NULL COMMENT '녹화 대상 트랙 종류. MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO, CAMERA' AFTER `session_participant_id`,
    ADD COLUMN `livekit_track_sid`      VARCHAR(255) NULL COMMENT 'Egress 시작 시 지정한 LiveKit Track SID. webhook 페이로드에 track 정보가 없을 때의 정본' AFTER `track_source`;

-- recording_files 에는 화자 컬럼이 이미 있으므로 트랙 종류만 더한다. 사후 전사가 마이크 트랙만 골라내는 데 쓴다
-- (화면 공유 오디오는 녹화하되 MVP 전사 대상에서 제외한다).
ALTER TABLE `recording_files`
    ADD COLUMN `track_source` VARCHAR(30) NULL COMMENT '녹화된 트랙 종류. MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO' AFTER `file_type`;
