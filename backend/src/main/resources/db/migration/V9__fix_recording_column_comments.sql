-- recording_files 의 컬럼 주석을 실제 저장 방식에 맞춘다.
-- 근거: .agents/frd.md §4.2·§15.2(S3 폐기, EC2 로컬 저장),
--       .agents/livekit-backend-guide.md §13·§14·§18(트랙별 Egress, 세션 루트 기준 상대 경로)
--
-- V1 을 고치지 않고 새 버전을 내는 이유: V1 은 이미 공유 환경에 적용됐다. 적용된 마이그레이션의
-- 내용을 바꾸면 체크섬이 어긋나고, validate-on-migrate 가 켜져 있어 그 환경의 애플리케이션이
-- 기동하지 못한다. V5 가 같은 이유로 주석만 새 버전에서 고친 선례를 따른다.
--
-- 데이터는 건드리지 않는다. 주석만 바꾸므로 잠금이 짧고 되돌릴 것이 없다.
-- MODIFY COLUMN 은 정의 전체를 다시 쓰므로 타입·NULL 허용 여부를 V1 원본 그대로 옮겼다.

ALTER TABLE `recording_files`
    -- 'HLS, COMPOSITE 또는 AUDIO' 였다. 합성 녹화(HLS)를 전제한 값이고, 실제로 들어가는 값인
    -- TRACK 이 목록에 없었다. 지금 이 컬럼에 쓰이는 값은 RecordingFile.TYPE_TRACK 하나뿐이다.
    -- 쓰이지 않는 값은 지우지 않고 남긴다. 후처리 병합 산출물을 이 표에 기록하게 되면(티켓 269)
    -- COMPOSITE 가 실제로 쓰일 수 있어, 그 결정과 함께 정리하는 편이 맞다.
    MODIFY COLUMN `file_type` VARCHAR(30) NOT NULL
        COMMENT '파일 종류. 현재 Track Egress 만 저장하므로 TRACK 만 쓰인다(HLS, COMPOSITE, AUDIO 는 미사용)',
    -- '비공개 S3 객체 키' 였다. S3 는 폐기됐고 실제 값은 세션 루트 기준 상대 경로다.
    -- 예: raw/instructor/instructor-microphone-TR_xxx,
    --     raw/participants/student-001/student-001-screen-share-audio-TR_xxx
    MODIFY COLUMN `storage_key` VARCHAR(500) NOT NULL
        COMMENT 'EC2 로컬 세션 디렉터리 기준 상대 경로. 절대 경로·드라이브 문자·상위 탈출을 넣지 않는다',
    -- '개별 오디오의 LiveKit Track SID' 였다. 오디오만이 아니라 저장 대상 트랙 전부에 쓰인다.
    -- 한 Egress 가 세그먼트를 여러 개 내면 첫 행만 SID 를 갖고 나머지는 NULL 로 남는다
    -- (UK(recording_id, livekit_track_sid) 가 트랙당 한 행만 허용한다).
    MODIFY COLUMN `livekit_track_sid` VARCHAR(255) NULL
        COMMENT '저장한 트랙의 LiveKit Track SID. 같은 트랙의 두 번째 세그먼트부터는 NULL';
