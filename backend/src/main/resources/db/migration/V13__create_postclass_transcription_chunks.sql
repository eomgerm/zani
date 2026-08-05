-- 사후 전사의 청크 단위 체크포인트(S15P11A105-247).
--
-- 왜 별도 테이블인가: 최종 결과가 들어가는 `transcripts` 는 UK_TRANSCRIPTS_SESSION 때문에 세션당 한 행이다.
-- 그 한 행에는 "3번 청크는 성공, 5번은 실패" 같은 부분 진행을 표현할 자리가 없다. 티켓의 완료 조건이
-- "청크 1개 실패 시 성공 청크 재호출 없이 완료" 이므로 진행 상태를 담을 곳이 필요하다.
--
-- 왜 시각 구간을 저장하는가: 절대 시간축의 정본은 FFmpeg segment muxer 가 내놓는 CSV 의 원본 시각이다.
-- 임시 청크 파일은 처리 후 삭제하므로 재시도할 때 같은 원본을 다시 분할해야 하는데, 저장해 둔 구간과 새로
-- 나온 CSV 를 대조하면 경계가 어긋났을 때 조용히 시간축이 밀리는 대신 명확히 실패할 수 있다.
-- (standalone ffprobe duration 을 누적하면 청크마다 6.5ms 씩 밀린다 — 실측. CSV 를 쓰는 이유다.)
--
-- pipeline_jobs·recording_outbox 와 같은 이유로 FK 를 두지 않는다: 소비·정리가 독립적인 작업 테이블이다.
CREATE TABLE `postclass_transcription_chunks`
(
    `id`                BIGINT       NOT NULL COMMENT 'TSID 기본키',
    `session_id`        BIGINT       NOT NULL COMMENT '전사 대상 세션 ID',
    `recording_file_id` BIGINT       NOT NULL COMMENT '분할 대상 원본 트랙 파일(recording_files.id)',
    `chunk_index`       INT          NOT NULL COMMENT '원본 안에서의 청크 순번(0부터)',
    `start_offset_ms`   BIGINT       NOT NULL COMMENT 'FFmpeg segment CSV 의 원본 기준 시작 시각(ms). 절대 시간축 계산의 정본',
    `end_offset_ms`     BIGINT       NOT NULL COMMENT 'FFmpeg segment CSV 의 원본 기준 종료 시각(ms)',
    `status`            VARCHAR(30)  NOT NULL COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED 또는 SKIPPED_SILENT',
    `attempt_count`     INT          NOT NULL DEFAULT 0 COMMENT '이 청크의 GMS 호출 시도 횟수',
    `lease_until`       DATETIME(6)  NULL COMMENT 'PROCESSING 으로 선점한 시각의 만료점. 서버가 죽어 남은 행을 이 시각 이후에 회수한다',
    `next_attempt_at`   DATETIME(6)  NULL COMMENT '이 시각 이후에 재시도할 수 있다. 대기 중이 아니면 NULL',
    `result_document`   JSON         NULL COMMENT '성공한 청크의 전사 결과. 세그먼트 시각은 청크 기준 상대값이고 병합 시 start_offset_ms 를 더한다',
    `last_error`        VARCHAR(500) NULL COMMENT '마지막 실패 사유',
    `created_at`        DATETIME(6)  NOT NULL COMMENT '행 생성 시각',
    `updated_at`        DATETIME(6)  NOT NULL COMMENT '상태 변경 시각',
    CONSTRAINT `PK_POSTCLASS_TRANSCRIPTION_CHUNKS` PRIMARY KEY (`id`),
    -- 같은 원본의 같은 순번은 하나뿐이다. 재분할해도 행이 늘지 않는 근거이자, 성공 청크를 다시 호출하지 않는 근거다.
    CONSTRAINT `UK_POSTCLASS_TRANSCRIPTION_CHUNKS_FILE_INDEX` UNIQUE (`recording_file_id`, `chunk_index`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 오케스트레이션은 한 세션의 미완료 청크를 순번대로 가져간다.
CREATE INDEX `IX_POSTCLASS_TRANSCRIPTION_CHUNKS_SESSION_STATUS` ON `postclass_transcription_chunks` (`session_id`, `status`, `chunk_index`);
