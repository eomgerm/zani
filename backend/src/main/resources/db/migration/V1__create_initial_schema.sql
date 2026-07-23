-- ZANI MVP database schema
-- Target: MySQL 9.7.1 / InnoDB / utf8mb4 / utf8mb4_0900_ai_ci
-- IDs are application-assigned TSIDs stored as BIGINT.
-- DATETIME(6) values are stored in UTC by application convention.


CREATE TABLE `group_alerts` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '집단 알림이 발생한 세션 ID',
	`alert_type`	VARCHAR(50)	NOT NULL	COMMENT '가장 강한 집단 알림 유형',
	`window_started_offset_ms`	BIGINT	NOT NULL	COMMENT '집계 구간 시작 시각(ms)',
	`window_ended_offset_ms`	BIGINT	NOT NULL	COMMENT '집계 구간 종료 시각(ms)',
	`numerator_count`	INT	NOT NULL	COMMENT '조건을 만족한 고유 학생 수',
	`denominator_count`	INT	NOT NULL	COMMENT '1분 이상 연속 접속한 학생 수',
	`occurred_offset_ms`	BIGINT	NOT NULL	COMMENT '알림 발생 시각(ms)',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '알림 생성 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `group_alert_response_counts` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`group_alert_id`	BIGINT	NOT NULL	COMMENT '응답 분포가 집계된 집단 알림 ID',
	`response_type`	VARCHAR(30)	NOT NULL	COMMENT 'OK, CONFUSED, MISSED 또는 NO_RESPONSE',
	`response_count`	INT	NOT NULL	COMMENT '응답 유형에 해당하는 학생 수',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '응답 분포 생성 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `check_prompt_evidences` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`check_prompt_id`	BIGINT	NOT NULL	COMMENT '근거가 연결된 체크 프롬프트 ID',
	`attention_event_id`	BIGINT	NULL	COMMENT '분석 이벤트 근거 ID',
	`interaction_event_id`	BIGINT	NULL	COMMENT '상호작용 이벤트 근거 ID',
	`evidence_type`	VARCHAR(50)	NOT NULL	COMMENT '근거의 역할 또는 유형',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '근거 연결 생성 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `instructor_notes` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '메모 대상 세션 ID',
	`instructor_participant_id`	BIGINT	NOT NULL	COMMENT '메모를 작성한 강사 세션 참여자 ID',
	`content`	TEXT	NULL	COMMENT '세션 전체 메모, 최대 5000자이며 빈 값 허용',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'DRAFT 또는 FINALIZED',
	`last_edited_at`	DATETIME(6)	NULL	COMMENT '마지막 입력 발생 시각',
	`finalized_at`	DATETIME(6)	NULL	COMMENT '수동 또는 자동 최종 확정 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '메모 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '메모 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_status_changes` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '상태가 변경된 세션 ID',
	`from_status`	VARCHAR(30)	NULL	COMMENT '변경 전 미팅 상태, LIVE 또는 ENDED',
	`to_status`	VARCHAR(30)	NOT NULL	COMMENT '변경 후 미팅 상태, LIVE 또는 ENDED',
	`changed_at`	DATETIME(6)	NOT NULL	COMMENT '상태 전이 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '행 생성 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `student_reports` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '리포트 대상 세션 ID',
	`session_participant_id`	BIGINT	NOT NULL	COMMENT '개인 리포트를 받는 학생 세션 참여자 ID',
	`participation_summary`	TEXT	NOT NULL	COMMENT 'AI가 생성한 학생 개인 참여도 요약',
	`published_at`	DATETIME(6)	NULL	COMMENT '개인 리포트 공개 완료 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '개인 리포트 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '개인 리포트 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `review_recommendations` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`student_report_id`	BIGINT	NOT NULL	COMMENT '복습 추천이 포함된 학생 리포트 ID',
	`recommendation_type`	VARCHAR(30)	NOT NULL	COMMENT 'CONFUSED, MISSED, QUESTION 또는 REPEAT',
	`title`	VARCHAR(200)	NOT NULL	COMMENT '복습 추천 제목',
	`description`	TEXT	NOT NULL	COMMENT 'AI가 생성한 복습 추천 설명',
	`started_offset_ms`	BIGINT	NOT NULL	COMMENT '추천 복습 구간 시작 시각(ms)',
	`ended_offset_ms`	BIGINT	NOT NULL	COMMENT '추천 복습 구간 종료 시각(ms)',
	`priority`	TINYINT	NOT NULL	COMMENT '추천 우선순위, 값이 작을수록 먼저 노출',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '복습 추천 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '복습 추천 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `attention_events` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '분석 이벤트가 발생한 세션 ID',
	`session_participant_id`	BIGINT	NOT NULL	COMMENT '분석 대상 학생 세션 참여자 ID',
	`attention_score`	TINYINT	NOT NULL	COMMENT '해당 시점의 참여도 점수 0~4',
	`occurred_offset_ms`	BIGINT	NOT NULL	COMMENT '세션 시작 기준 참여도 측정 시각(ms)',
	`confidence`	DECIMAL(5, 4)	NULL	COMMENT '상태 신뢰도 0~1',
	`signal_quality`	DECIMAL(5, 4)	NULL	COMMENT '입력 신호 품질 0~1',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '이벤트 저장 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `members` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`google_subject`	VARCHAR(255)	NOT NULL	COMMENT 'Google OAuth 사용자 고유 식별자',
	`email`	VARCHAR(255)	NOT NULL	COMMENT 'Google 계정 이메일',
	`display_name`	VARCHAR(100)	NOT NULL	COMMENT '서비스에 표시할 현재 이름',
	`profile_image_url`	VARCHAR(500)	NULL	COMMENT 'Google 프로필 이미지 주소',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '계정 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '계정 수정 시각',
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각',
	`retention_expires_at`	DATETIME(6)	NULL	COMMENT '최종 파기 예정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sessions` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`host_member_id`	BIGINT	NOT NULL	COMMENT '세션을 생성하고 진행하는 강사 회원 ID',
	`title`	VARCHAR(100)	NOT NULL	COMMENT '수업 제목, 최대 100자',
	`invite_code`	CHAR(8)	NOT NULL	COMMENT '링크와 직접 입력에 함께 쓰는 참여 코드',
	`status`	VARCHAR(30)	NOT NULL	COMMENT '미팅 상태, LIVE 또는 ENDED',
	`analysis_status`	VARCHAR(30)	NOT NULL	COMMENT '분석 상태, NOT_STARTED, WAITING_FOR_NOTE, PROCESSING, COMPLETED 또는 FAILED',
	`started_at`	DATETIME(6)	NOT NULL	COMMENT '수업 시작 시각',
	`ended_at`	DATETIME(6)	NULL	COMMENT '수업 종료 시각',
	`note_due_at`	DATETIME(6)	NULL	COMMENT '강사 메모 자동 확정 예정 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '세션 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '세션 수정 시각',
	`deleted_at`	DATETIME(6)	NULL	COMMENT '소프트 삭제 시각',
	`retention_expires_at`	DATETIME(6)	NULL	COMMENT '최종 파기 예정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_reports` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '리포트 대상 세션 ID',
	`summary`	TEXT	NOT NULL	COMMENT '강사와 학생에게 공통으로 공개할 AI 수업 요약',
	`published_at`	DATETIME(6)	NULL	COMMENT '리포트 공개 완료 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '리포트 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '리포트 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_sections` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '수업 내용 구간이 속한 세션 ID',
	`title`	VARCHAR(200)	NOT NULL	COMMENT '수업 내용 구간 제목',
	`summary`	TEXT	NULL	COMMENT '수업 내용 구간 요약',
	`started_offset_ms`	BIGINT	NOT NULL	COMMENT '수업 내용 구간 시작 시각(ms)',
	`ended_offset_ms`	BIGINT	NOT NULL	COMMENT '수업 내용 구간 종료 시각(ms)',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '수업 내용 구간 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '수업 내용 구간 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `instructor_reports` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '강사 리포트 대상 세션 ID',
	`overall_feedback`	TEXT	NOT NULL	COMMENT 'AI가 생성한 수업 종합 피드백',
	`published_at`	DATETIME(6)	NULL	COMMENT '강사 리포트 공개 완료 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '강사 리포트 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '강사 리포트 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `instructor_report_scores` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`instructor_report_id`	BIGINT	NOT NULL	COMMENT '평가 점수가 포함된 강사 리포트 ID',
	`evaluation_type`	VARCHAR(30)	NOT NULL	COMMENT 'DELIVERY, STRUCTURE_FLOW, INTERACTION 또는 DIFFICULTY_CONTROL',
	`score`	TINYINT	NOT NULL	COMMENT '분야별 평가 점수 0~100',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '평가 점수 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '평가 점수 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `instructor_report_insights` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`instructor_report_id`	BIGINT	NOT NULL	COMMENT '인사이트가 포함된 강사 리포트 ID',
	`insight_type`	VARCHAR(30)	NOT NULL	COMMENT 'AI 인사이트 유형',
	`content`	TEXT	NOT NULL	COMMENT 'AI가 생성한 인사이트 내용',
	`started_offset_ms`	BIGINT	NULL	COMMENT '인사이트 대상 구간 시작 시각(ms), 전체 수업 대상이면 NULL',
	`ended_offset_ms`	BIGINT	NULL	COMMENT '인사이트 대상 구간 종료 시각(ms), 전체 수업 대상이면 NULL',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '인사이트 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '인사이트 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `instructor_report_tips` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`instructor_report_id`	BIGINT	NOT NULL	COMMENT '개선 팁이 포함된 강사 리포트 ID',
	`tip_type`	VARCHAR(30)	NOT NULL	COMMENT 'AI 수업 개선 팁 유형',
	`title`	VARCHAR(200)	NOT NULL	COMMENT '개선 팁 제목',
	`content`	TEXT	NOT NULL	COMMENT 'AI가 생성한 개선 팁 내용',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '개선 팁 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '개선 팁 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat_messages` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '메시지가 발생한 세션 ID',
	`sender_participant_id`	BIGINT	NOT NULL	COMMENT '메시지를 보낸 세션 참여자 ID',
	`recipient_participant_id`	BIGINT	NULL	COMMENT '비공개 메시지 수신자 ID, 공개 채팅은 NULL',
	`channel_type`	VARCHAR(20)	NOT NULL	COMMENT 'PUBLIC 또는 PRIVATE',
	`content`	VARCHAR(1000)	NOT NULL	COMMENT '메시지 본문, 최대 1000자',
	`occurred_offset_ms`	BIGINT	NOT NULL	COMMENT '세션 시작 기준 발생 시각(ms)',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '메시지 저장 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `interaction_events` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '상호작용이 발생한 세션 ID',
	`actor_participant_id`	BIGINT	NULL	COMMENT '행위를 수행한 세션 참여자 ID, 시스템 이벤트는 NULL',
	`event_type`	VARCHAR(50)	NOT NULL	COMMENT '손들기, 이모지, 미디어 상태, 강사 제어 유형',
	`occurred_offset_ms`	BIGINT	NOT NULL	COMMENT '세션 시작 기준 발생 시각(ms)',
	`payload`	JSON	NOT NULL	COMMENT '이벤트 유형별 추가 데이터',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '이벤트 저장 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `transcripts` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '전사 대상 세션 ID',
	`transcript_document`	JSON	NOT NULL	COMMENT '화자와 시간 구간을 포함한 전체 전사 JSON',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '전사 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '전사 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quizzes` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`student_report_id`	BIGINT	NOT NULL	COMMENT 'AI 퀴즈가 포함된 학생 리포트 ID',
	`title`	VARCHAR(200)	NOT NULL	COMMENT 'AI 퀴즈 제목',
	`description`	TEXT	NULL	COMMENT 'AI 퀴즈 설명',
	`estimated_duration_minutes`	SMALLINT	NULL	COMMENT '예상 풀이 시간(분)',
	`published_at`	DATETIME(6)	NULL	COMMENT '퀴즈 공개 완료 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_questions` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`quiz_id`	BIGINT	NOT NULL	COMMENT '문제가 포함된 AI 퀴즈 ID',
	`question_text`	TEXT	NOT NULL	COMMENT '퀴즈 문제 내용',
	`explanation`	TEXT	NULL	COMMENT '답안 제출 후 제공할 해설',
	`question_order`	INT	NOT NULL	COMMENT '퀴즈 내 문제 순서',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 문제 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 문제 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_options` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`quiz_question_id`	BIGINT	NOT NULL	COMMENT '선택지가 포함된 퀴즈 문제 ID',
	`option_text`	TEXT	NOT NULL	COMMENT '퀴즈 선택지 내용',
	`is_correct`	BOOLEAN	NOT NULL	COMMENT '정답 선택지 여부',
	`option_order`	INT	NOT NULL	COMMENT '문제 내 선택지 순서',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 선택지 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 선택지 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `quiz_answers` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`quiz_question_id`	BIGINT	NOT NULL	COMMENT '응답한 퀴즈 문제 ID',
	`selected_quiz_option_id`	BIGINT	NOT NULL	COMMENT '학생이 선택한 퀴즈 선택지 ID',
	`answered_at`	DATETIME(6)	NOT NULL	COMMENT '문제 응답 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 응답 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '퀴즈 응답 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `recording_files` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '녹화 파일이 속한 세션 ID',
	`recording_id`	BIGINT	NOT NULL	COMMENT '파일을 만든 녹화 실행 ID',
	`session_participant_id`	BIGINT	NULL	COMMENT '개별 음성 파일의 화자 세션 참여자 ID',
	`file_type`	VARCHAR(30)	NOT NULL	COMMENT 'HLS, COMPOSITE 또는 AUDIO',
	`storage_key`	VARCHAR(500)	NOT NULL	COMMENT '비공개 S3 객체 키',
	`livekit_track_sid`	VARCHAR(255)	NULL	COMMENT '개별 오디오의 LiveKit Track SID',
	`started_offset_ms`	BIGINT	NULL	COMMENT '파일 또는 세그먼트 시작 시각(ms)',
	`ended_offset_ms`	BIGINT	NULL	COMMENT '파일 또는 세그먼트 종료 시각(ms)',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '파일 행 생성 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `check_prompts` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '프롬프트가 발생한 세션 ID',
	`session_participant_id`	BIGINT	NOT NULL	COMMENT '프롬프트 대상 학생 세션 참여자 ID',
	`trigger_type`	VARCHAR(50)	NOT NULL	COMMENT '프롬프트를 발생시킨 규칙 유형',
	`status`	VARCHAR(20)	NOT NULL	COMMENT 'OPEN, RESPONDED 또는 TIMEOUT',
	`response`	VARCHAR(30)	NULL	COMMENT 'OK, CONFUSED, MISSED 또는 NO_RESPONSE',
	`shown_offset_ms`	BIGINT	NOT NULL	COMMENT '프롬프트 표시 시각(ms)',
	`responded_offset_ms`	BIGINT	NULL	COMMENT '응답 시각(ms), 미응답은 NULL',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '프롬프트 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '응답 상태 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `session_participants` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '참여한 세션 ID',
	`member_id`	BIGINT	NOT NULL	COMMENT '참여 회원 ID',
	`role`	VARCHAR(20)	NOT NULL	COMMENT 'INSTRUCTOR 또는 STUDENT',
	`first_joined_at`	DATETIME(6)	NOT NULL	COMMENT '최초 입장 또는 강사 등록 시각',
	`last_accessed_at`	DATETIME(6)	NULL	COMMENT '가장 최근 세션 접근 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '참여자 행 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '참여자 행 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `recordings` (
	`id`	BIGINT	NOT NULL	COMMENT 'TSID 기본키',
	`session_id`	BIGINT	NOT NULL	COMMENT '녹화를 수행한 세션 ID',
	`livekit_egress_id`	VARCHAR(255)	NOT NULL	COMMENT 'LiveKit Egress 실행 식별자',
	`recording_type`	VARCHAR(30)	NOT NULL	COMMENT 'ROOM_COMPOSITE 또는 TRACK',
	`attempt_number`	INT	NOT NULL	COMMENT '최초 실행과 1회 재시도 순번',
	`status`	VARCHAR(30)	NOT NULL	COMMENT 'STARTING, RECORDING, COMPLETE, PARTIAL, FAILED',
	`started_at`	DATETIME(6)	NULL	COMMENT 'Egress 녹화 시작 시각',
	`ended_at`	DATETIME(6)	NULL	COMMENT 'Egress 녹화 종료 시각',
	`created_at`	DATETIME(6)	NOT NULL	COMMENT '녹화 행 생성 시각',
	`updated_at`	DATETIME(6)	NOT NULL	COMMENT '녹화 상태 수정 시각'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `group_alerts` ADD CONSTRAINT `PK_GROUP_ALERTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `group_alert_response_counts` ADD CONSTRAINT `PK_GROUP_ALERT_RESPONSE_COUNTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `check_prompt_evidences` ADD CONSTRAINT `PK_CHECK_PROMPT_EVIDENCES` PRIMARY KEY (
	`id`
);

ALTER TABLE `instructor_notes` ADD CONSTRAINT `PK_INSTRUCTOR_NOTES` PRIMARY KEY (
	`id`
);

ALTER TABLE `session_status_changes` ADD CONSTRAINT `PK_SESSION_STATUS_CHANGES` PRIMARY KEY (
	`id`
);

ALTER TABLE `student_reports` ADD CONSTRAINT `PK_STUDENT_REPORTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `review_recommendations` ADD CONSTRAINT `PK_REVIEW_RECOMMENDATIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `attention_events` ADD CONSTRAINT `PK_ATTENTION_EVENTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `members` ADD CONSTRAINT `PK_MEMBERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `sessions` ADD CONSTRAINT `PK_SESSIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `session_reports` ADD CONSTRAINT `PK_SESSION_REPORTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `session_sections` ADD CONSTRAINT `PK_SESSION_SECTIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `instructor_reports` ADD CONSTRAINT `PK_INSTRUCTOR_REPORTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `instructor_report_scores` ADD CONSTRAINT `PK_INSTRUCTOR_REPORT_SCORES` PRIMARY KEY (
	`id`
);

ALTER TABLE `instructor_report_insights` ADD CONSTRAINT `PK_INSTRUCTOR_REPORT_INSIGHTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `instructor_report_tips` ADD CONSTRAINT `PK_INSTRUCTOR_REPORT_TIPS` PRIMARY KEY (
	`id`
);

ALTER TABLE `chat_messages` ADD CONSTRAINT `PK_CHAT_MESSAGES` PRIMARY KEY (
	`id`
);

ALTER TABLE `interaction_events` ADD CONSTRAINT `PK_INTERACTION_EVENTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `transcripts` ADD CONSTRAINT `PK_TRANSCRIPTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `quizzes` ADD CONSTRAINT `PK_QUIZZES` PRIMARY KEY (
	`id`
);

ALTER TABLE `quiz_questions` ADD CONSTRAINT `PK_QUIZ_QUESTIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `quiz_options` ADD CONSTRAINT `PK_QUIZ_OPTIONS` PRIMARY KEY (
	`id`
);

ALTER TABLE `quiz_answers` ADD CONSTRAINT `PK_QUIZ_ANSWERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `recording_files` ADD CONSTRAINT `PK_RECORDING_FILES` PRIMARY KEY (
	`id`
);

ALTER TABLE `check_prompts` ADD CONSTRAINT `PK_CHECK_PROMPTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `session_participants` ADD CONSTRAINT `PK_SESSION_PARTICIPANTS` PRIMARY KEY (
	`id`
);

ALTER TABLE `recordings` ADD CONSTRAINT `PK_RECORDINGS` PRIMARY KEY (
	`id`
);

ALTER TABLE `members` ADD CONSTRAINT `UK_MEMBERS_GOOGLE_SUBJECT` UNIQUE (
	`google_subject`
);

ALTER TABLE `sessions` ADD CONSTRAINT `UK_SESSIONS_INVITE_CODE` UNIQUE (
	`invite_code`
);

ALTER TABLE `instructor_notes` ADD CONSTRAINT `UK_INSTRUCTOR_NOTES_SESSION` UNIQUE (
	`session_id`
);

ALTER TABLE `session_reports` ADD CONSTRAINT `UK_SESSION_REPORTS_SESSION` UNIQUE (
	`session_id`
);

ALTER TABLE `instructor_reports` ADD CONSTRAINT `UK_INSTRUCTOR_REPORTS_SESSION` UNIQUE (
	`session_id`
);

ALTER TABLE `student_reports` ADD CONSTRAINT `UK_STUDENT_REPORTS_SESSION_PARTICIPANT` UNIQUE (
	`session_id`,
	`session_participant_id`
);

ALTER TABLE `transcripts` ADD CONSTRAINT `UK_TRANSCRIPTS_SESSION` UNIQUE (
	`session_id`
);

ALTER TABLE `instructor_report_scores` ADD CONSTRAINT `UK_INSTRUCTOR_REPORT_SCORES_REPORT_TYPE` UNIQUE (
	`instructor_report_id`,
	`evaluation_type`
);

ALTER TABLE `quizzes` ADD CONSTRAINT `UK_QUIZZES_STUDENT_REPORT` UNIQUE (
	`student_report_id`
);

ALTER TABLE `quiz_questions` ADD CONSTRAINT `UK_QUIZ_QUESTIONS_QUIZ_ORDER` UNIQUE (
	`quiz_id`,
	`question_order`
);

ALTER TABLE `quiz_options` ADD CONSTRAINT `UK_QUIZ_OPTIONS_QUIZ_QUESTION_ORDER` UNIQUE (
	`quiz_question_id`,
	`option_order`
);

ALTER TABLE `quiz_options` ADD CONSTRAINT `UK_QUIZ_OPTIONS_QUIZ_QUESTION_OPTION` UNIQUE (
	`quiz_question_id`,
	`id`
);

ALTER TABLE `quiz_answers` ADD CONSTRAINT `UK_QUIZ_ANSWERS_QUIZ_QUESTION` UNIQUE (
	`quiz_question_id`
);

ALTER TABLE `group_alert_response_counts` ADD CONSTRAINT `UK_GROUP_ALERT_RESPONSE_COUNTS_ALERT_TYPE` UNIQUE (
	`group_alert_id`,
	`response_type`
);

ALTER TABLE `recordings` ADD CONSTRAINT `UK_RECORDINGS_LIVEKIT_EGRESS` UNIQUE (
	`livekit_egress_id`
);

ALTER TABLE `recording_files` ADD CONSTRAINT `UK_RECORDING_FILES_STORAGE_KEY` UNIQUE (
	`storage_key`
);

ALTER TABLE `recording_files` ADD CONSTRAINT `UK_RECORDING_FILES_RECORDING_TRACK` UNIQUE (
	`recording_id`,
	`livekit_track_sid`
);

ALTER TABLE `session_participants` ADD CONSTRAINT `UK_SESSION_PARTICIPANTS_SESSION_MEMBER` UNIQUE (
	`session_id`,
	`member_id`
);

ALTER TABLE `session_participants` ADD CONSTRAINT `UK_SESSION_PARTICIPANTS_SESSION_PARTICIPANT` UNIQUE (
	`session_id`,
	`id`
);

ALTER TABLE `recordings` ADD CONSTRAINT `UK_RECORDINGS_SESSION_RECORDING` UNIQUE (
	`session_id`,
	`id`
);

ALTER TABLE `group_alerts` ADD CONSTRAINT `FK_GROUP_ALERTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `group_alert_response_counts` ADD CONSTRAINT `FK_GROUP_ALERT_RESPONSE_COUNTS_GROUP_ALERT` FOREIGN KEY (
	`group_alert_id`
)
REFERENCES `group_alerts` (
	`id`
);

ALTER TABLE `check_prompt_evidences` ADD CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_CHECK_PROMPT` FOREIGN KEY (
	`check_prompt_id`
)
REFERENCES `check_prompts` (
	`id`
);

ALTER TABLE `check_prompt_evidences` ADD CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_ATTENTION_EVENT` FOREIGN KEY (
	`attention_event_id`
)
REFERENCES `attention_events` (
	`id`
);

ALTER TABLE `check_prompt_evidences` ADD CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_INTERACTION_EVENT` FOREIGN KEY (
	`interaction_event_id`
)
REFERENCES `interaction_events` (
	`id`
);

ALTER TABLE `check_prompt_evidences` ADD CONSTRAINT `CK_CHECK_PROMPT_EVIDENCES_EXACTLY_ONE_SOURCE` CHECK (
	(`attention_event_id` IS NOT NULL) <> (`interaction_event_id` IS NOT NULL)
);

ALTER TABLE `instructor_notes` ADD CONSTRAINT `FK_INSTRUCTOR_NOTES_SESSION_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`instructor_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `session_status_changes` ADD CONSTRAINT `FK_SESSION_STATUS_CHANGES_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `student_reports` ADD CONSTRAINT `FK_STUDENT_REPORTS_SESSION_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`session_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `review_recommendations` ADD CONSTRAINT `FK_REVIEW_RECOMMENDATIONS_STUDENT_REPORT` FOREIGN KEY (
	`student_report_id`
)
REFERENCES `student_reports` (
	`id`
);

ALTER TABLE `attention_events` ADD CONSTRAINT `FK_ATTENTION_EVENTS_SESSION_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`session_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `sessions` ADD CONSTRAINT `FK_SESSIONS_HOST_MEMBER` FOREIGN KEY (
	`host_member_id`
)
REFERENCES `members` (
	`id`
);

ALTER TABLE `session_reports` ADD CONSTRAINT `FK_SESSION_REPORTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `session_sections` ADD CONSTRAINT `FK_SESSION_SECTIONS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `instructor_reports` ADD CONSTRAINT `FK_INSTRUCTOR_REPORTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `instructor_report_scores` ADD CONSTRAINT `FK_INSTRUCTOR_REPORT_SCORES_INSTRUCTOR_REPORT` FOREIGN KEY (
	`instructor_report_id`
)
REFERENCES `instructor_reports` (
	`id`
);

ALTER TABLE `instructor_report_insights` ADD CONSTRAINT `FK_INSTRUCTOR_REPORT_INSIGHTS_INSTRUCTOR_REPORT` FOREIGN KEY (
	`instructor_report_id`
)
REFERENCES `instructor_reports` (
	`id`
);

ALTER TABLE `instructor_report_tips` ADD CONSTRAINT `FK_INSTRUCTOR_REPORT_TIPS_INSTRUCTOR_REPORT` FOREIGN KEY (
	`instructor_report_id`
)
REFERENCES `instructor_reports` (
	`id`
);

ALTER TABLE `chat_messages` ADD CONSTRAINT `FK_CHAT_MESSAGES_SENDER_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`sender_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `chat_messages` ADD CONSTRAINT `FK_CHAT_MESSAGES_RECIPIENT_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`recipient_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `interaction_events` ADD CONSTRAINT `FK_INTERACTION_EVENTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `interaction_events` ADD CONSTRAINT `FK_INTERACTION_EVENTS_ACTOR_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`actor_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `transcripts` ADD CONSTRAINT `FK_TRANSCRIPTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `quizzes` ADD CONSTRAINT `FK_QUIZZES_STUDENT_REPORT` FOREIGN KEY (
	`student_report_id`
)
REFERENCES `student_reports` (
	`id`
);

ALTER TABLE `quiz_questions` ADD CONSTRAINT `FK_QUIZ_QUESTIONS_QUIZ` FOREIGN KEY (
	`quiz_id`
)
REFERENCES `quizzes` (
	`id`
);

ALTER TABLE `quiz_options` ADD CONSTRAINT `FK_QUIZ_OPTIONS_QUIZ_QUESTION` FOREIGN KEY (
	`quiz_question_id`
)
REFERENCES `quiz_questions` (
	`id`
);

ALTER TABLE `quiz_answers` ADD CONSTRAINT `FK_QUIZ_ANSWERS_SELECTED_QUIZ_OPTION` FOREIGN KEY (
	`quiz_question_id`,
	`selected_quiz_option_id`
)
REFERENCES `quiz_options` (
	`quiz_question_id`,
	`id`
);

ALTER TABLE `recording_files` ADD CONSTRAINT `FK_RECORDING_FILES_RECORDING` FOREIGN KEY (
	`session_id`,
	`recording_id`
)
REFERENCES `recordings` (
	`session_id`,
	`id`
);

ALTER TABLE `recording_files` ADD CONSTRAINT `FK_RECORDING_FILES_SESSION_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`session_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `check_prompts` ADD CONSTRAINT `FK_CHECK_PROMPTS_SESSION_PARTICIPANT` FOREIGN KEY (
	`session_id`,
	`session_participant_id`
)
REFERENCES `session_participants` (
	`session_id`,
	`id`
);

ALTER TABLE `session_participants` ADD CONSTRAINT `FK_SESSION_PARTICIPANTS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

ALTER TABLE `session_participants` ADD CONSTRAINT `FK_SESSION_PARTICIPANTS_MEMBER` FOREIGN KEY (
	`member_id`
)
REFERENCES `members` (
	`id`
);

ALTER TABLE `recordings` ADD CONSTRAINT `FK_RECORDINGS_SESSION` FOREIGN KEY (
	`session_id`
)
REFERENCES `sessions` (
	`id`
);

CREATE INDEX `IX_SESSION_SECTIONS_SESSION_STARTED_OFFSET`
ON `session_sections` (`session_id`, `started_offset_ms`);

CREATE INDEX `IX_ATTENTION_EVENTS_SESSION_PARTICIPANT_OCCURRED_OFFSET`
ON `attention_events` (`session_id`, `session_participant_id`, `occurred_offset_ms`);

CREATE INDEX `IX_ATTENTION_EVENTS_SESSION_OCCURRED_OFFSET_SCORE`
ON `attention_events` (`session_id`, `occurred_offset_ms`, `attention_score`);

CREATE INDEX `IX_INTERACTION_EVENTS_SESSION_OCCURRED_OFFSET`
ON `interaction_events` (`session_id`, `occurred_offset_ms`);

CREATE INDEX `IX_CHAT_MESSAGES_SESSION_OCCURRED_OFFSET`
ON `chat_messages` (`session_id`, `occurred_offset_ms`);

CREATE INDEX `IX_CHECK_PROMPTS_SESSION_PARTICIPANT_SHOWN_OFFSET`
ON `check_prompts` (`session_id`, `session_participant_id`, `shown_offset_ms`);

CREATE INDEX `IX_REVIEW_RECOMMENDATIONS_REPORT_PRIORITY_STARTED_OFFSET`
ON `review_recommendations` (`student_report_id`, `priority`, `started_offset_ms`);
