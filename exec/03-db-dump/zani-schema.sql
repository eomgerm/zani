-- ZANI database schema dump
-- MySQL: 8.4.10
-- Applied migrations: 20
-- Tables: 34
-- Data, accounts and passwords are intentionally excluded.

-- MySQL dump 10.13  Distrib 8.4.10, for Linux (x86_64)
--
-- Host: localhost    Database: zani
-- ------------------------------------------------------
-- Server version	8.4.10

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `attention_events`
--

DROP TABLE IF EXISTS `attention_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `attention_events` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '분석 이벤트가 발생한 세션 ID',
  `session_participant_id` bigint NOT NULL COMMENT '분석 대상 학생 세션 참여자 ID',
  `detector_outcome` varchar(30) DEFAULT NULL COMMENT '검출기 출력 7종. NOT_ENGAGED, BARELY_ENGAGED, ENGAGED, HIGHLY_ENGAGED, UNMEASURABLE, CAMERA_OFF, DETECTOR_UNAVAILABLE',
  `attention_score` tinyint DEFAULT NULL COMMENT '4단계 참여도 1~4. 4단계가 아닌 출력은 NULL',
  `occurred_offset_ms` bigint NOT NULL COMMENT '세션 시작 기준 참여도 측정 시각(ms)',
  `window_started_offset_ms` bigint DEFAULT NULL COMMENT '10초 판정 창 시작 시각(ms). 즉시 확정 출력(CAMERA_OFF, DETECTOR_UNAVAILABLE)은 창이 없어 NULL',
  `confidence` decimal(5,4) DEFAULT NULL COMMENT '상태 신뢰도 0~1',
  `signal_quality` decimal(5,4) DEFAULT NULL COMMENT '입력 신호 품질 0~1',
  `created_at` datetime(6) NOT NULL COMMENT '이벤트 저장 시각',
  `low_engagement` tinyint(1) DEFAULT NULL COMMENT '브라우저가 확률 합 0.35 기준으로 판단한 저참여 여부. 4단계 출력에만 있다',
  `feature_schema_version` varchar(40) DEFAULT NULL COMMENT '브라우저가 쓴 특징 추출 계약 버전(예: mediapipe_98_v1)',
  `engine_version` varchar(40) DEFAULT NULL COMMENT '브라우저가 쓴 추론 엔진·모델 버전',
  `client_event_id` varchar(64) DEFAULT NULL COMMENT '클라이언트가 만든 이벤트 식별자. 재시도 멱등의 기준',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_ATTENTION_EVENTS_CLIENT_EVENT` (`session_id`,`session_participant_id`,`client_event_id`),
  KEY `IX_ATTENTION_EVENTS_SESSION_PARTICIPANT_OCCURRED_OFFSET` (`session_id`,`session_participant_id`,`occurred_offset_ms`),
  KEY `IX_ATTENTION_EVENTS_SESSION_OCCURRED_OFFSET_SCORE` (`session_id`,`occurred_offset_ms`,`attention_score`),
  CONSTRAINT `FK_ATTENTION_EVENTS_SESSION_PARTICIPANT` FOREIGN KEY (`session_id`, `session_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_messages`
--

DROP TABLE IF EXISTS `chat_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_messages` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '메시지가 발생한 세션 ID',
  `sender_participant_id` bigint NOT NULL COMMENT '메시지를 보낸 세션 참여자 ID',
  `recipient_participant_id` bigint DEFAULT NULL COMMENT '비공개 메시지 수신자 ID, 공개 채팅은 NULL',
  `channel_type` varchar(20) NOT NULL COMMENT 'PUBLIC 또는 PRIVATE',
  `content` varchar(1000) NOT NULL COMMENT '메시지 본문, 최대 1000자',
  `occurred_offset_ms` bigint NOT NULL COMMENT '세션 시작 기준 발생 시각(ms)',
  `created_at` datetime(6) NOT NULL COMMENT '메시지 저장 시각',
  PRIMARY KEY (`id`),
  KEY `FK_CHAT_MESSAGES_SENDER_PARTICIPANT` (`session_id`,`sender_participant_id`),
  KEY `FK_CHAT_MESSAGES_RECIPIENT_PARTICIPANT` (`session_id`,`recipient_participant_id`),
  KEY `IX_CHAT_MESSAGES_SESSION_OCCURRED_OFFSET` (`session_id`,`occurred_offset_ms`),
  CONSTRAINT `FK_CHAT_MESSAGES_RECIPIENT_PARTICIPANT` FOREIGN KEY (`session_id`, `recipient_participant_id`) REFERENCES `session_participants` (`session_id`, `id`),
  CONSTRAINT `FK_CHAT_MESSAGES_SENDER_PARTICIPANT` FOREIGN KEY (`session_id`, `sender_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `check_prompt_evidences`
--

DROP TABLE IF EXISTS `check_prompt_evidences`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `check_prompt_evidences` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `check_prompt_id` bigint NOT NULL COMMENT '근거가 연결된 체크 프롬프트 ID',
  `attention_event_id` bigint DEFAULT NULL COMMENT '분석 이벤트 근거 ID',
  `interaction_event_id` bigint DEFAULT NULL COMMENT '상호작용 이벤트 근거 ID',
  `evidence_type` varchar(50) NOT NULL COMMENT '근거의 역할 또는 유형',
  `created_at` datetime(6) NOT NULL COMMENT '근거 연결 생성 시각',
  PRIMARY KEY (`id`),
  KEY `FK_CHECK_PROMPT_EVIDENCES_CHECK_PROMPT` (`check_prompt_id`),
  KEY `FK_CHECK_PROMPT_EVIDENCES_ATTENTION_EVENT` (`attention_event_id`),
  KEY `FK_CHECK_PROMPT_EVIDENCES_INTERACTION_EVENT` (`interaction_event_id`),
  CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_ATTENTION_EVENT` FOREIGN KEY (`attention_event_id`) REFERENCES `attention_events` (`id`),
  CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_CHECK_PROMPT` FOREIGN KEY (`check_prompt_id`) REFERENCES `check_prompts` (`id`),
  CONSTRAINT `FK_CHECK_PROMPT_EVIDENCES_INTERACTION_EVENT` FOREIGN KEY (`interaction_event_id`) REFERENCES `interaction_events` (`id`),
  CONSTRAINT `CK_CHECK_PROMPT_EVIDENCES_EXACTLY_ONE_SOURCE` CHECK (((`attention_event_id` is not null) <> (`interaction_event_id` is not null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `check_prompts`
--

DROP TABLE IF EXISTS `check_prompts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `check_prompts` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '프롬프트가 발생한 세션 ID',
  `session_participant_id` bigint NOT NULL COMMENT '프롬프트 대상 학생 세션 참여자 ID',
  `trigger_type` varchar(50) NOT NULL COMMENT '프롬프트 종류. 서버가 기록하는 것은 UNDERSTANDING_CHECK 뿐이다(자세·카메라 안내는 브라우저 안에서 끝난다)',
  `status` varchar(20) NOT NULL COMMENT 'OPEN, RESPONDED 또는 TIMEOUT',
  `response` varchar(30) DEFAULT NULL COMMENT 'OK, CONFUSED, MISSED 또는 NON_RESPONSE',
  `shown_offset_ms` bigint NOT NULL COMMENT '프롬프트 표시 시각(ms)',
  `responded_offset_ms` bigint DEFAULT NULL COMMENT '응답 시각(ms), 미응답은 NULL',
  `created_at` datetime(6) NOT NULL COMMENT '프롬프트 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '응답 상태 수정 시각',
  PRIMARY KEY (`id`),
  KEY `IX_CHECK_PROMPTS_SESSION_PARTICIPANT_SHOWN_OFFSET` (`session_id`,`session_participant_id`,`shown_offset_ms`),
  CONSTRAINT `FK_CHECK_PROMPTS_SESSION_PARTICIPANT` FOREIGN KEY (`session_id`, `session_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coaching_histories`
--

DROP TABLE IF EXISTS `coaching_histories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coaching_histories` (
  `id` bigint NOT NULL COMMENT 'TSID primary key',
  `session_id` bigint NOT NULL COMMENT 'Lecture session that owns this coaching result',
  `trigger_id` varchar(64) NOT NULL COMMENT 'Idempotency key unique inside the session',
  `triggered_at` datetime(6) NOT NULL COMMENT 'Absolute UTC time when coaching was triggered',
  `completed_at` datetime(6) NOT NULL COMMENT 'Absolute UTC time when coaching processing completed',
  `denominator_count` int NOT NULL COMMENT 'Anonymous students included in the trigger snapshot',
  `selected_tip_type` varchar(50) DEFAULT NULL COMMENT 'Tip type selected before generation',
  `outcome_status` varchar(30) NOT NULL COMMENT 'TIP_DELIVERED or TIP_UNAVAILABLE',
  `transcript_status` varchar(30) NOT NULL COMMENT 'TRANSCRIBED, SKIPPED_NOT_REQUIRED, NOT_ATTEMPTED, TRANSCRIPTION_FAILED, or NO_TRANSCRIPT',
  `transcript_started_at` datetime(6) DEFAULT NULL COMMENT 'Absolute UTC start of the real-time transcript interval',
  `transcript_ended_at` datetime(6) DEFAULT NULL COMMENT 'Absolute UTC end of the real-time transcript interval',
  `topic` varchar(500) DEFAULT NULL COMMENT 'Topic extracted from the real-time transcript without retaining the full text',
  `tip_type` varchar(50) DEFAULT NULL COMMENT 'Delivered coaching tip type',
  `tip_title` varchar(200) DEFAULT NULL COMMENT 'Delivered coaching tip title',
  `tip_message` text COMMENT 'Delivered coaching tip message',
  `unavailable_reason` varchar(50) DEFAULT NULL COMMENT 'Reason a coaching tip was not delivered',
  `created_at` datetime(6) NOT NULL COMMENT 'Persistence time in UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_COACHING_HISTORIES_SESSION_TRIGGER` (`session_id`,`trigger_id`),
  KEY `IX_COACHING_HISTORIES_SESSION_TRIGGERED_AT` (`session_id`,`triggered_at`),
  CONSTRAINT `FK_COACHING_HISTORIES_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`),
  CONSTRAINT `CK_COACHING_HISTORIES_OUTCOME` CHECK ((((`outcome_status` = _utf8mb4'TIP_DELIVERED') and (`tip_type` is not null) and (`unavailable_reason` is null)) or ((`outcome_status` = _utf8mb4'TIP_UNAVAILABLE') and (`tip_type` is null) and (`unavailable_reason` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `coaching_history_response_counts`
--

DROP TABLE IF EXISTS `coaching_history_response_counts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coaching_history_response_counts` (
  `id` bigint NOT NULL COMMENT 'TSID primary key',
  `coaching_history_id` bigint NOT NULL COMMENT 'Owning coaching history row',
  `response_type` varchar(30) NOT NULL COMMENT 'SIGNIFICANT, CONFUSED, MISSED, NON_RESPONSE, or UNMEASURABLE',
  `response_count` int NOT NULL COMMENT 'Exact anonymous student count',
  `created_at` datetime(6) NOT NULL COMMENT 'Persistence time in UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_COACHING_HISTORY_RESPONSE_COUNTS_HISTORY_TYPE` (`coaching_history_id`,`response_type`),
  CONSTRAINT `FK_COACHING_HISTORY_RESPONSE_COUNTS_HISTORY` FOREIGN KEY (`coaching_history_id`) REFERENCES `coaching_histories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `group_alert_response_counts`
--

DROP TABLE IF EXISTS `group_alert_response_counts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_alert_response_counts` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `group_alert_id` bigint NOT NULL COMMENT '응답 분포가 집계된 집단 알림 ID',
  `response_type` varchar(30) NOT NULL COMMENT 'OK, CONFUSED, MISSED 또는 NO_RESPONSE',
  `response_count` int NOT NULL COMMENT '응답 유형에 해당하는 학생 수',
  `created_at` datetime(6) NOT NULL COMMENT '응답 분포 생성 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_GROUP_ALERT_RESPONSE_COUNTS_ALERT_TYPE` (`group_alert_id`,`response_type`),
  CONSTRAINT `FK_GROUP_ALERT_RESPONSE_COUNTS_GROUP_ALERT` FOREIGN KEY (`group_alert_id`) REFERENCES `group_alerts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `group_alerts`
--

DROP TABLE IF EXISTS `group_alerts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `group_alerts` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '집단 알림이 발생한 세션 ID',
  `alert_type` varchar(50) NOT NULL COMMENT '가장 강한 집단 알림 유형',
  `window_started_offset_ms` bigint NOT NULL COMMENT '집계 구간 시작 시각(ms)',
  `window_ended_offset_ms` bigint NOT NULL COMMENT '집계 구간 종료 시각(ms)',
  `numerator_count` int NOT NULL COMMENT '조건을 만족한 고유 학생 수',
  `denominator_count` int NOT NULL COMMENT '1분 이상 연속 접속한 학생 수',
  `occurred_offset_ms` bigint NOT NULL COMMENT '알림 발생 시각(ms)',
  `created_at` datetime(6) NOT NULL COMMENT '알림 생성 시각',
  PRIMARY KEY (`id`),
  KEY `FK_GROUP_ALERTS_SESSION` (`session_id`),
  CONSTRAINT `FK_GROUP_ALERTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `instructor_notes`
--

DROP TABLE IF EXISTS `instructor_notes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `instructor_notes` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '메모 대상 세션 ID',
  `instructor_participant_id` bigint NOT NULL COMMENT '메모를 작성한 강사 세션 참여자 ID',
  `content` text COMMENT '세션 전체 메모, 최대 5000자이며 빈 값 허용',
  `status` varchar(20) NOT NULL COMMENT 'DRAFT 또는 FINALIZED',
  `last_edited_at` datetime(6) DEFAULT NULL COMMENT '마지막 입력 발생 시각',
  `finalized_at` datetime(6) DEFAULT NULL COMMENT '수동 또는 자동 최종 확정 시각',
  `created_at` datetime(6) NOT NULL COMMENT '메모 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '메모 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_INSTRUCTOR_NOTES_SESSION` (`session_id`),
  KEY `FK_INSTRUCTOR_NOTES_SESSION_PARTICIPANT` (`session_id`,`instructor_participant_id`),
  CONSTRAINT `FK_INSTRUCTOR_NOTES_SESSION_PARTICIPANT` FOREIGN KEY (`session_id`, `instructor_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `instructor_report_insights`
--

DROP TABLE IF EXISTS `instructor_report_insights`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `instructor_report_insights` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `instructor_report_id` bigint NOT NULL COMMENT '인사이트가 포함된 강사 리포트 ID',
  `title` varchar(200) DEFAULT NULL COMMENT '인사이트 제목. AI 가 유형 목록 없이 직접 짓는다',
  `content` text NOT NULL COMMENT 'AI가 생성한 인사이트 내용',
  `suggestion` text COMMENT 'AI 가 제시한 개선 제안. content 는 그 제안의 근거다',
  `started_offset_ms` bigint DEFAULT NULL COMMENT '인사이트 대상 구간 시작 시각(ms), 전체 수업 대상이면 NULL',
  `ended_offset_ms` bigint DEFAULT NULL COMMENT '인사이트 대상 구간 종료 시각(ms), 전체 수업 대상이면 NULL',
  `created_at` datetime(6) NOT NULL COMMENT '인사이트 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '인사이트 수정 시각',
  PRIMARY KEY (`id`),
  KEY `FK_INSTRUCTOR_REPORT_INSIGHTS_INSTRUCTOR_REPORT` (`instructor_report_id`),
  CONSTRAINT `FK_INSTRUCTOR_REPORT_INSIGHTS_INSTRUCTOR_REPORT` FOREIGN KEY (`instructor_report_id`) REFERENCES `instructor_reports` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `instructor_report_scores`
--

DROP TABLE IF EXISTS `instructor_report_scores`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `instructor_report_scores` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `instructor_report_id` bigint NOT NULL COMMENT '평가 점수가 포함된 강사 리포트 ID',
  `evaluation_type` varchar(30) NOT NULL COMMENT 'DELIVERY, STRUCTURE_FLOW, INTERACTION 또는 DIFFICULTY_CONTROL',
  `score` tinyint NOT NULL COMMENT '분야별 평가 점수 0~100',
  `created_at` datetime(6) NOT NULL COMMENT '평가 점수 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '평가 점수 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_INSTRUCTOR_REPORT_SCORES_REPORT_TYPE` (`instructor_report_id`,`evaluation_type`),
  CONSTRAINT `FK_INSTRUCTOR_REPORT_SCORES_INSTRUCTOR_REPORT` FOREIGN KEY (`instructor_report_id`) REFERENCES `instructor_reports` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `instructor_reports`
--

DROP TABLE IF EXISTS `instructor_reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `instructor_reports` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '강사 리포트 대상 세션 ID',
  `overall_feedback` text NOT NULL COMMENT 'AI가 생성한 수업 종합 피드백',
  `question_count` int DEFAULT NULL COMMENT '수업 전체에서 학생들이 남긴 질문 수. AI 가 공개 채팅에서 질문인 발화만 세어 판단한다',
  `published_at` datetime(6) DEFAULT NULL COMMENT '강사 리포트 공개 완료 시각',
  `created_at` datetime(6) NOT NULL COMMENT '강사 리포트 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '강사 리포트 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_INSTRUCTOR_REPORTS_SESSION` (`session_id`),
  CONSTRAINT `FK_INSTRUCTOR_REPORTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `interaction_events`
--

DROP TABLE IF EXISTS `interaction_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `interaction_events` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '상호작용이 발생한 세션 ID',
  `actor_participant_id` bigint DEFAULT NULL COMMENT '행위를 수행한 세션 참여자 ID, 시스템 이벤트는 NULL',
  `event_type` varchar(50) NOT NULL COMMENT '손들기, 이모지, 미디어 상태, 강사 제어 유형',
  `occurred_offset_ms` bigint NOT NULL COMMENT '세션 시작 기준 발생 시각(ms)',
  `payload` json NOT NULL COMMENT '이벤트 유형별 추가 데이터',
  `created_at` datetime(6) NOT NULL COMMENT '이벤트 저장 시각',
  PRIMARY KEY (`id`),
  KEY `FK_INTERACTION_EVENTS_ACTOR_PARTICIPANT` (`session_id`,`actor_participant_id`),
  KEY `IX_INTERACTION_EVENTS_SESSION_OCCURRED_OFFSET` (`session_id`,`occurred_offset_ms`),
  CONSTRAINT `FK_INTERACTION_EVENTS_ACTOR_PARTICIPANT` FOREIGN KEY (`session_id`, `actor_participant_id`) REFERENCES `session_participants` (`session_id`, `id`),
  CONSTRAINT `FK_INTERACTION_EVENTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `members`
--

DROP TABLE IF EXISTS `members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `members` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `google_subject` varchar(255) NOT NULL COMMENT 'Google OAuth 사용자 고유 식별자',
  `email` varchar(255) NOT NULL COMMENT 'Google 계정 이메일',
  `display_name` varchar(100) NOT NULL COMMENT '서비스에 표시할 현재 이름',
  `profile_image_url` varchar(500) DEFAULT NULL COMMENT 'Google 프로필 이미지 주소',
  `report_email_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '강의 리포트 완료 이메일 수신 여부. 기본 TRUE',
  `created_at` datetime(6) NOT NULL COMMENT '계정 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '계정 수정 시각',
  `deleted_at` datetime(6) DEFAULT NULL COMMENT '소프트 삭제 시각',
  `retention_expires_at` datetime(6) DEFAULT NULL COMMENT '최종 파기 예정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_MEMBERS_GOOGLE_SUBJECT` (`google_subject`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notification_outbox`
--

DROP TABLE IF EXISTS `notification_outbox`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification_outbox` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '알림 대상 세션 ID',
  `member_id` bigint NOT NULL COMMENT '수신 회원 ID',
  `email` varchar(255) NOT NULL COMMENT '발견 시점 스냅샷 이메일',
  `display_name` varchar(100) DEFAULT NULL COMMENT '발견 시점 스냅샷 이름',
  `type` varchar(40) NOT NULL COMMENT '알림 유형. 현재 REPORT_READY',
  `dedup_key` varchar(200) NOT NULL COMMENT '세션·수신자·유형 유일 키(멱등 등록)',
  `status` varchar(30) NOT NULL COMMENT '발송 상태. PENDING, IN_PROGRESS, SENT 또는 FAILED',
  `attempt_count` int NOT NULL COMMENT 'consumer 선점 횟수(백오프·재시도 상한 근거)',
  `next_attempt_at` datetime(6) NOT NULL COMMENT '이 시각 이후에 다음 시도 가능(백오프)',
  `last_error` varchar(500) DEFAULT NULL COMMENT '마지막 발송 실패 사유(실패 기록)',
  `sent_at` datetime(6) DEFAULT NULL COMMENT '발송 성공 시각',
  `created_at` datetime(6) NOT NULL COMMENT '행 생성 시각(= 알림 등록 시각)',
  `updated_at` datetime(6) NOT NULL COMMENT '상태 변경 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_NOTIFICATION_OUTBOX_DEDUP` (`dedup_key`),
  KEY `IX_NOTIFICATION_OUTBOX_STATUS_NEXT` (`status`,`next_attempt_at`),
  KEY `IX_NOTIFICATION_OUTBOX_SESSION_TYPE` (`session_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pipeline_jobs`
--

DROP TABLE IF EXISTS `pipeline_jobs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_jobs` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '사후 처리 대상 세션 ID',
  `status` varchar(30) NOT NULL COMMENT '처리 단계. QUEUED, TRANSCRIBING, ANALYZING, VALIDATING, PUBLISHED 또는 FAILED',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '현재 단계의 시도 횟수(백오프·재시도 상한 근거)',
  `next_attempt_at` datetime(6) DEFAULT NULL COMMENT '이 시각 이후에 현재 단계를 재시도할 수 있다. 대기 중이 아니면 NULL',
  `last_error` varchar(500) DEFAULT NULL COMMENT '마지막 실패 사유(재시도·최종 실패 기록)',
  `created_at` datetime(6) NOT NULL COMMENT '행 생성 시각(= 메모 확정 시각)',
  `updated_at` datetime(6) NOT NULL COMMENT '단계 변경 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_PIPELINE_JOBS_SESSION` (`session_id`),
  KEY `IX_PIPELINE_JOBS_STATUS_CREATED_AT` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `postclass_transcription_chunks`
--

DROP TABLE IF EXISTS `postclass_transcription_chunks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `postclass_transcription_chunks` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '전사 대상 세션 ID',
  `recording_file_id` bigint NOT NULL COMMENT '분할 대상 원본 트랙 파일(recording_files.id)',
  `chunk_index` int NOT NULL COMMENT '원본 안에서의 청크 순번(0부터)',
  `start_offset_ms` bigint NOT NULL COMMENT 'FFmpeg segment CSV 의 원본 기준 시작 시각(ms). 절대 시간축 계산의 정본',
  `end_offset_ms` bigint NOT NULL COMMENT 'FFmpeg segment CSV 의 원본 기준 종료 시각(ms)',
  `status` varchar(30) NOT NULL COMMENT 'PENDING, PROCESSING, SUCCEEDED, FAILED 또는 SKIPPED_SILENT',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '이 청크의 GMS 호출 시도 횟수',
  `lease_until` datetime(6) DEFAULT NULL COMMENT 'PROCESSING 으로 선점한 시각의 만료점. 서버가 죽어 남은 행을 이 시각 이후에 회수한다',
  `next_attempt_at` datetime(6) DEFAULT NULL COMMENT '이 시각 이후에 재시도할 수 있다. 대기 중이 아니면 NULL',
  `result_document` json DEFAULT NULL COMMENT '성공한 청크의 전사 결과. 세그먼트 시각은 청크 기준 상대값이고 병합 시 start_offset_ms 를 더한다',
  `last_error` varchar(500) DEFAULT NULL COMMENT '마지막 실패 사유',
  `created_at` datetime(6) NOT NULL COMMENT '행 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '상태 변경 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_POSTCLASS_TRANSCRIPTION_CHUNKS_FILE_INDEX` (`recording_file_id`,`chunk_index`),
  KEY `IX_POSTCLASS_TRANSCRIPTION_CHUNKS_SESSION_STATUS` (`session_id`,`status`,`chunk_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `quiz_answers`
--

DROP TABLE IF EXISTS `quiz_answers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `quiz_answers` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `quiz_question_id` bigint NOT NULL COMMENT '응답한 퀴즈 문제 ID',
  `selected_quiz_option_id` bigint NOT NULL COMMENT '학생이 선택한 퀴즈 선택지 ID',
  `answered_at` datetime(6) NOT NULL COMMENT '문제 응답 시각',
  `created_at` datetime(6) NOT NULL COMMENT '퀴즈 응답 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '퀴즈 응답 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_QUIZ_ANSWERS_QUIZ_QUESTION` (`quiz_question_id`),
  KEY `FK_QUIZ_ANSWERS_SELECTED_QUIZ_OPTION` (`quiz_question_id`,`selected_quiz_option_id`),
  CONSTRAINT `FK_QUIZ_ANSWERS_SELECTED_QUIZ_OPTION` FOREIGN KEY (`quiz_question_id`, `selected_quiz_option_id`) REFERENCES `quiz_options` (`quiz_question_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `quiz_options`
--

DROP TABLE IF EXISTS `quiz_options`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `quiz_options` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `quiz_question_id` bigint NOT NULL COMMENT '선택지가 포함된 퀴즈 문제 ID',
  `option_text` text NOT NULL COMMENT '퀴즈 선택지 내용',
  `is_correct` tinyint(1) NOT NULL COMMENT '정답 선택지 여부',
  `option_order` int NOT NULL COMMENT '문제 내 선택지 순서',
  `created_at` datetime(6) NOT NULL COMMENT '퀴즈 선택지 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '퀴즈 선택지 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_QUIZ_OPTIONS_QUIZ_QUESTION_ORDER` (`quiz_question_id`,`option_order`),
  UNIQUE KEY `UK_QUIZ_OPTIONS_QUIZ_QUESTION_OPTION` (`quiz_question_id`,`id`),
  CONSTRAINT `FK_QUIZ_OPTIONS_QUIZ_QUESTION` FOREIGN KEY (`quiz_question_id`) REFERENCES `quiz_questions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `quiz_questions`
--

DROP TABLE IF EXISTS `quiz_questions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `quiz_questions` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `quiz_id` bigint NOT NULL COMMENT '문제가 포함된 AI 퀴즈 ID',
  `question_text` text NOT NULL COMMENT '퀴즈 문제 내용',
  `explanation` text COMMENT '답안 제출 후 제공할 해설',
  `section_started_offset_ms` bigint DEFAULT NULL COMMENT '문항이 가리키는 개념 설명 구간의 시작 시각(ms). 근거 구간을 특정하지 못했으면 NULL',
  `question_order` int NOT NULL COMMENT '퀴즈 내 문제 순서',
  `created_at` datetime(6) NOT NULL COMMENT '퀴즈 문제 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '퀴즈 문제 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_QUIZ_QUESTIONS_QUIZ_ORDER` (`quiz_id`,`question_order`),
  CONSTRAINT `FK_QUIZ_QUESTIONS_QUIZ` FOREIGN KEY (`quiz_id`) REFERENCES `quizzes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `quizzes`
--

DROP TABLE IF EXISTS `quizzes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `quizzes` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `student_report_id` bigint NOT NULL COMMENT 'AI 퀴즈가 포함된 학생 리포트 ID',
  `title` varchar(200) NOT NULL COMMENT 'AI 퀴즈 제목',
  `description` text COMMENT 'AI 퀴즈 설명',
  `estimated_duration_minutes` smallint DEFAULT NULL COMMENT '예상 풀이 시간(분)',
  `published_at` datetime(6) DEFAULT NULL COMMENT '퀴즈 공개 완료 시각',
  `created_at` datetime(6) NOT NULL COMMENT '퀴즈 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '퀴즈 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_QUIZZES_STUDENT_REPORT` (`student_report_id`),
  CONSTRAINT `FK_QUIZZES_STUDENT_REPORT` FOREIGN KEY (`student_report_id`) REFERENCES `student_reports` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recording_files`
--

DROP TABLE IF EXISTS `recording_files`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recording_files` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '녹화 파일이 속한 세션 ID',
  `recording_id` bigint NOT NULL COMMENT '파일을 만든 녹화 실행 ID',
  `session_participant_id` bigint DEFAULT NULL COMMENT '개별 음성 파일의 화자 세션 참여자 ID',
  `file_type` varchar(30) NOT NULL COMMENT '파일 종류. 현재 Track Egress 만 저장하므로 TRACK 만 쓰인다(HLS, COMPOSITE, AUDIO 는 미사용)',
  `track_source` varchar(30) DEFAULT NULL COMMENT '녹화된 트랙 종류. MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO, CAMERA(강사만)',
  `storage_key` varchar(500) NOT NULL COMMENT 'EC2 로컬 세션 디렉터리 기준 상대 경로. 절대 경로·드라이브 문자·상위 탈출을 넣지 않는다',
  `livekit_track_sid` varchar(255) DEFAULT NULL COMMENT '저장한 트랙의 LiveKit Track SID. 같은 트랙의 두 번째 세그먼트부터는 NULL',
  `started_offset_ms` bigint DEFAULT NULL COMMENT '파일 또는 세그먼트 시작 시각(ms)',
  `ended_offset_ms` bigint DEFAULT NULL COMMENT '파일 또는 세그먼트 종료 시각(ms)',
  `created_at` datetime(6) NOT NULL COMMENT '파일 행 생성 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_RECORDING_FILES_STORAGE_KEY` (`storage_key`),
  UNIQUE KEY `UK_RECORDING_FILES_RECORDING_TRACK` (`recording_id`,`livekit_track_sid`),
  KEY `FK_RECORDING_FILES_RECORDING` (`session_id`,`recording_id`),
  KEY `FK_RECORDING_FILES_SESSION_PARTICIPANT` (`session_id`,`session_participant_id`),
  CONSTRAINT `FK_RECORDING_FILES_RECORDING` FOREIGN KEY (`session_id`, `recording_id`) REFERENCES `recordings` (`session_id`, `id`),
  CONSTRAINT `FK_RECORDING_FILES_SESSION_PARTICIPANT` FOREIGN KEY (`session_id`, `session_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recording_finalization_jobs`
--

DROP TABLE IF EXISTS `recording_finalization_jobs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recording_finalization_jobs` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '최종 병합 대상 세션 ID',
  `status` varchar(30) NOT NULL COMMENT 'PENDING, RUNNING, COMPLETED, FAILED',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '실제 worker 실행 횟수. Egress 정리 대기는 세지 않는다',
  `lease_token` int NOT NULL DEFAULT '0' COMMENT 'RUNNING 실행권 fencing token',
  `lease_until` datetime(6) DEFAULT NULL COMMENT 'RUNNING 실행권 만료 시각',
  `next_attempt_at` datetime(6) DEFAULT NULL COMMENT '다음 실행 가능 시각',
  `last_error` varchar(500) DEFAULT NULL COMMENT '마지막 실패 사유. 경로·자격증명·원문은 넣지 않는다',
  `manifest_path` varchar(500) DEFAULT NULL COMMENT '컨테이너 내부 manifest 경로',
  `output_path` varchar(500) DEFAULT NULL COMMENT '컨테이너 내부 최종 MP4 경로',
  `output_size_bytes` bigint DEFAULT NULL COMMENT '완성된 lecture.mp4 크기',
  `output_sha256` char(64) DEFAULT NULL COMMENT '완성된 lecture.mp4 SHA-256',
  `started_at` datetime(6) DEFAULT NULL COMMENT '첫 worker 실행 시작 시각',
  `completed_at` datetime(6) DEFAULT NULL COMMENT '최종 MP4 확정 시각',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_RECORDING_FINALIZATION_JOBS_SESSION` (`session_id`),
  KEY `IX_RECORDING_FINALIZATION_JOBS_DUE` (`status`,`next_attempt_at`,`lease_until`,`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recording_outbox`
--

DROP TABLE IF EXISTS `recording_outbox`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recording_outbox` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `dedup_key` varchar(200) NOT NULL COMMENT '중복 방지 키(예: track:{sessionId}:{trackSid})',
  `outbox_type` varchar(40) NOT NULL COMMENT '작업 종류(START_TRACK_EGRESS)',
  `session_id` bigint NOT NULL COMMENT '대상 세션 ID',
  `payload` varchar(2000) NOT NULL COMMENT '작업 수행에 필요한 데이터(JSON, 익명 alias만 포함)',
  `status` varchar(20) NOT NULL COMMENT 'PENDING, IN_PROGRESS, COMPLETED 또는 FAILED',
  `attempt_count` int NOT NULL COMMENT '릴레이 시도 횟수(claim 시점에 증가)',
  `next_attempt_at` datetime(6) NOT NULL COMMENT '이 시각 이후에만 릴레이가 소비한다(지수 백오프)',
  `last_error` varchar(500) DEFAULT NULL COMMENT '마지막 실패 사유',
  `created_at` datetime(6) NOT NULL COMMENT '행 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '상태 수정 시각(IN_PROGRESS lease 만료 판단에 사용)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_RECORDING_OUTBOX_DEDUP_KEY` (`dedup_key`),
  KEY `IX_RECORDING_OUTBOX_STATUS_NEXT_ATTEMPT_AT` (`status`,`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recording_webhook_events`
--

DROP TABLE IF EXISTS `recording_webhook_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recording_webhook_events` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `event_id` varchar(100) NOT NULL COMMENT 'LiveKit webhook 이벤트 ID',
  `event_type` varchar(60) NOT NULL COMMENT 'track_published, egress_ended 등',
  `payload` text NOT NULL COMMENT '수신 원문(JSON) — 재처리·감사용',
  `status` varchar(20) NOT NULL COMMENT 'RECEIVED 또는 PROCESSED',
  `created_at` datetime(6) NOT NULL COMMENT '수신 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '상태 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_RECORDING_WEBHOOK_EVENTS_EVENT_ID` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `recordings`
--

DROP TABLE IF EXISTS `recordings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `recordings` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '녹화를 수행한 세션 ID',
  `livekit_egress_id` varchar(255) NOT NULL COMMENT 'LiveKit Egress 실행 식별자',
  `session_participant_id` bigint DEFAULT NULL COMMENT '이 Egress 가 녹화하는 트랙의 발행자 세션 참여자 ID. 종료 시 recording_files 로 옮긴다',
  `track_source` varchar(30) DEFAULT NULL COMMENT '녹화 대상 트랙 종류. MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO, CAMERA(강사만)',
  `livekit_track_sid` varchar(255) DEFAULT NULL COMMENT 'Egress 시작 시 지정한 LiveKit Track SID. webhook 페이로드에 track 정보가 없을 때의 정본',
  `recording_type` varchar(30) NOT NULL COMMENT 'ROOM_COMPOSITE 또는 TRACK',
  `attempt_number` int NOT NULL COMMENT '최초 실행과 1회 재시도 순번',
  `status` varchar(30) NOT NULL COMMENT 'STARTING, RECORDING, COMPLETE, PARTIAL, FAILED',
  `started_at` datetime(6) DEFAULT NULL COMMENT 'Egress 녹화 시작 시각',
  `ended_at` datetime(6) DEFAULT NULL COMMENT 'Egress 녹화 종료 시각',
  `created_at` datetime(6) NOT NULL COMMENT '녹화 행 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '녹화 상태 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_RECORDINGS_LIVEKIT_EGRESS` (`livekit_egress_id`),
  UNIQUE KEY `UK_RECORDINGS_SESSION_RECORDING` (`session_id`,`id`),
  CONSTRAINT `FK_RECORDINGS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `review_recommendations`
--

DROP TABLE IF EXISTS `review_recommendations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_recommendations` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `student_report_id` bigint NOT NULL COMMENT '복습 추천이 포함된 학생 리포트 ID',
  `recommendation_type` varchar(30) NOT NULL COMMENT 'CONFUSED, MISSED, NO_RESPONSE, LOW_ENGAGEMENT 또는 QUESTION',
  `title` varchar(200) NOT NULL COMMENT '복습 추천 제목',
  `description` text NOT NULL COMMENT 'AI가 생성한 복습 추천 설명',
  `started_offset_ms` bigint NOT NULL COMMENT '추천 복습 구간 시작 시각(ms)',
  `ended_offset_ms` bigint NOT NULL COMMENT '추천 복습 구간 종료 시각(ms)',
  `priority` tinyint NOT NULL COMMENT '추천 우선순위, 값이 작을수록 먼저 노출',
  `created_at` datetime(6) NOT NULL COMMENT '복습 추천 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '복습 추천 수정 시각',
  PRIMARY KEY (`id`),
  KEY `IX_REVIEW_RECOMMENDATIONS_REPORT_PRIORITY_STARTED_OFFSET` (`student_report_id`,`priority`,`started_offset_ms`),
  CONSTRAINT `FK_REVIEW_RECOMMENDATIONS_STUDENT_REPORT` FOREIGN KEY (`student_report_id`) REFERENCES `student_reports` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_participants`
--

DROP TABLE IF EXISTS `session_participants`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_participants` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '참여한 세션 ID',
  `member_id` bigint NOT NULL COMMENT '참여 회원 ID',
  `role` varchar(20) NOT NULL COMMENT 'INSTRUCTOR 또는 STUDENT',
  `first_joined_at` datetime(6) DEFAULT NULL COMMENT '실제 미디어 연결이 확인된 최초 시각. NULL 이면 사후 자료 접근 자격이 없다. 입장 API 호출로는 채우지 않는다',
  `last_accessed_at` datetime(6) DEFAULT NULL COMMENT '가장 최근 세션 접근 시각',
  `created_at` datetime(6) NOT NULL COMMENT '참여자 행 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '참여자 행 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_SESSION_PARTICIPANTS_SESSION_MEMBER` (`session_id`,`member_id`),
  UNIQUE KEY `UK_SESSION_PARTICIPANTS_SESSION_PARTICIPANT` (`session_id`,`id`),
  KEY `FK_SESSION_PARTICIPANTS_MEMBER` (`member_id`),
  CONSTRAINT `FK_SESSION_PARTICIPANTS_MEMBER` FOREIGN KEY (`member_id`) REFERENCES `members` (`id`),
  CONSTRAINT `FK_SESSION_PARTICIPANTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_reports`
--

DROP TABLE IF EXISTS `session_reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_reports` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '리포트 대상 세션 ID',
  `summary` text NOT NULL COMMENT '강사와 학생에게 공통으로 공개할 AI 수업 요약',
  `published_at` datetime(6) DEFAULT NULL COMMENT '리포트 공개 완료 시각',
  `created_at` datetime(6) NOT NULL COMMENT '리포트 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '리포트 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_SESSION_REPORTS_SESSION` (`session_id`),
  CONSTRAINT `FK_SESSION_REPORTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_sections`
--

DROP TABLE IF EXISTS `session_sections`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_sections` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '수업 내용 구간이 속한 세션 ID',
  `title` varchar(200) NOT NULL COMMENT '수업 내용 구간 제목',
  `summary` text COMMENT '수업 내용 구간 요약',
  `started_offset_ms` bigint NOT NULL COMMENT '수업 내용 구간 시작 시각(ms)',
  `ended_offset_ms` bigint NOT NULL COMMENT '수업 내용 구간 종료 시각(ms)',
  `created_at` datetime(6) NOT NULL COMMENT '수업 내용 구간 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '수업 내용 구간 수정 시각',
  PRIMARY KEY (`id`),
  KEY `IX_SESSION_SECTIONS_SESSION_STARTED_OFFSET` (`session_id`,`started_offset_ms`),
  CONSTRAINT `FK_SESSION_SECTIONS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `session_status_changes`
--

DROP TABLE IF EXISTS `session_status_changes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_status_changes` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '상태가 변경된 세션 ID',
  `from_status` varchar(30) DEFAULT NULL COMMENT '변경 전 미팅 상태, LIVE 또는 ENDED',
  `to_status` varchar(30) NOT NULL COMMENT '변경 후 미팅 상태, LIVE 또는 ENDED',
  `changed_at` datetime(6) NOT NULL COMMENT '상태 전이 시각',
  `created_at` datetime(6) NOT NULL COMMENT '행 생성 시각',
  PRIMARY KEY (`id`),
  KEY `FK_SESSION_STATUS_CHANGES_SESSION` (`session_id`),
  CONSTRAINT `FK_SESSION_STATUS_CHANGES_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sessions`
--

DROP TABLE IF EXISTS `sessions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sessions` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `host_member_id` bigint NOT NULL COMMENT '세션을 생성하고 진행하는 강사 회원 ID',
  `title` varchar(100) NOT NULL COMMENT '수업 제목, 최대 100자',
  `invite_code` char(8) NOT NULL COMMENT '링크와 직접 입력에 함께 쓰는 참여 코드',
  `status` varchar(30) NOT NULL COMMENT '미팅 상태, LIVE 또는 ENDED',
  `analysis_status` varchar(30) NOT NULL COMMENT '분석 상태, NOT_STARTED, WAITING_FOR_NOTE, PROCESSING, COMPLETED 또는 FAILED',
  `started_at` datetime(6) NOT NULL COMMENT '수업 시작 시각',
  `ended_at` datetime(6) DEFAULT NULL COMMENT '수업 종료 시각',
  `note_due_at` datetime(6) DEFAULT NULL COMMENT '강사 메모 자동 확정 예정 시각',
  `created_at` datetime(6) NOT NULL COMMENT '세션 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '세션 수정 시각',
  `deleted_at` datetime(6) DEFAULT NULL COMMENT '소프트 삭제 시각',
  `retention_expires_at` datetime(6) DEFAULT NULL COMMENT '최종 파기 예정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_SESSIONS_INVITE_CODE` (`invite_code`),
  KEY `FK_SESSIONS_HOST_MEMBER` (`host_member_id`),
  CONSTRAINT `FK_SESSIONS_HOST_MEMBER` FOREIGN KEY (`host_member_id`) REFERENCES `members` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `student_reports`
--

DROP TABLE IF EXISTS `student_reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `student_reports` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '리포트 대상 세션 ID',
  `session_participant_id` bigint NOT NULL COMMENT '개인 리포트를 받는 학생 세션 참여자 ID',
  `participation_summary` text NOT NULL COMMENT 'AI가 생성한 학생 개인 참여도 요약',
  `question_count` int DEFAULT NULL COMMENT '학생이 수업 중 남긴 질문 수. AI 가 공개 채팅에서 질문인 발화만 세어 판단한다',
  `published_at` datetime(6) DEFAULT NULL COMMENT '개인 리포트 공개 완료 시각',
  `created_at` datetime(6) NOT NULL COMMENT '개인 리포트 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '개인 리포트 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_STUDENT_REPORTS_SESSION_PARTICIPANT` (`session_id`,`session_participant_id`),
  CONSTRAINT `FK_STUDENT_REPORTS_SESSION_PARTICIPANT` FOREIGN KEY (`session_id`, `session_participant_id`) REFERENCES `session_participants` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `transcripts`
--

DROP TABLE IF EXISTS `transcripts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transcripts` (
  `id` bigint NOT NULL COMMENT 'TSID 기본키',
  `session_id` bigint NOT NULL COMMENT '전사 대상 세션 ID',
  `transcript_document` json NOT NULL COMMENT '화자와 시간 구간을 포함한 전체 전사 JSON',
  `created_at` datetime(6) NOT NULL COMMENT '전사 생성 시각',
  `updated_at` datetime(6) NOT NULL COMMENT '전사 수정 시각',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_TRANSCRIPTS_SESSION` (`session_id`),
  CONSTRAINT `FK_TRANSCRIPTS_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping events for database 'zani'
--

--
-- Dumping routines for database 'zani'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-07  1:29:55
