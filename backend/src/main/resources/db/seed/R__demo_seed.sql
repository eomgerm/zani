-- 시연용 목업 데이터. local·dev 프로필에서만 적용된다(application.yaml 의 flyway.locations).
--
-- 반복 마이그레이션(R__)으로 두는 이유: 시드는 스키마가 아니라 "지금 이 파일이 말하는 상태"를 DB 에 맞추는 일이다.
-- 내용을 고치면 다음 기동에서 다시 적용돼야 하는데, 버전 마이그레이션은 한 번 적용되면 다시 돌지 않는다.
-- 이 예외는 .agents/flyway-migration-guide.md §File Naming and Ordering 에 명시돼 있다.
--
-- 멱등: 파일 선두에서 시드 ID 대역만 FK 역순으로 지우고 다시 넣는다. 대역 밖(실제 TSID) 행은 건드리지 않는다.
--
-- ID 대역: 1000000000000 ~ 1000000999999 (1e12 대)만 쓴다. TSID 는 약 8×10^17 이라 겹치지 않는다.
--   1000000001xxx members — 1005~1024 가짜 학생 20명, 1901~1905 실제 계정을 못 찾았을 때 쓰는 대체 회원
--   1000000002xxx sessions           1000000003xxx session_participants
--   1000000004xxx session_status_changes                             1000000005xxx session_sections
--   1000000006xxx transcripts        1000000007xxx instructor_notes  1000000008xxx pipeline_jobs
--   1000000009xxx recordings         1000000010xxx recording_files   1000000011xxx check_prompts
--   1000000012xxx check_prompt_evidences                             1000000013xxx group_alerts
--   1000000014xxx group_alert_response_counts                        1000000015xxx coaching_histories
--   1000000016xxx coaching_history_response_counts                   1000000017xxx chat_messages
--   1000000018xxx interaction_events 1000000019xxx session_reports   1000000020xxx instructor_reports
--   1000000021xxx instructor_report_scores                           1000000022xxx instructor_report_insights
--   1000000023xxx instructor_report_tips                             1000000024xxx student_reports
--   1000000025xxx review_recommendations                             1000000026xxx quizzes
--   1000000027xxx quiz_questions     1000000028xxx quiz_options      1000000029xxx quiz_answers
--   1000000100000 ~ 1000000110212    attention_events (10,212행이라 별도 블록을 준다)
--
-- LIVE 세션은 넣지 않는다. 라이브는 실제로 시연하며, 유령 LIVE 세션이 남으면 "진행 중인 수업으로 돌아가기"가 그쪽을 잡는다.
--
-- 시각은 모두 UTC 다(V1 헤더의 애플리케이션 규약). 재적용해도 같은 값이 나오도록 NOW() 를 쓰지 않는다.


-- ============================================================================
-- 변수 블록 — 시연에 쓸 실제 계정을 여기서 정한다
-- ============================================================================
-- 이 이메일로 members 를 찾아 시연 세션의 참가자로 넣는다. google_subject 는 쓰지 않는다 — 그 값은
-- 구글 로그인이 만드는 것이라 시드가 지어내면 실제 로그인이 UK_MEMBERS_GOOGLE_SUBJECT 에 걸린다.
-- 따라서 조건은 하나다: 그 사람이 이 환경에서 한 번은 로그인해 members 행이 이미 있어야 한다.
-- 시드가 이미 적용된 뒤에 처음 로그인한 사람은 자동으로 붙지 않는다. 반복 마이그레이션은 체크섬이
-- 바뀔 때만 다시 돌기 때문이다. 그때는 이 파일을 mysql 로 직접 한 번 더 실행하면 된다.
--
-- 못 찾으면 그 자리는 시드 대역 가짜 회원이 대신 들어가고, 그 사람 화면에는 시연 세션이 뜨지 않는다.
-- scripts/verify-demo-seed.sql 이 다섯 자리의 매칭 결과를 보여준다.
SET @instructor_1_email = 'skyrider0618@gmail.com'; -- 김용휘 · 풀세트 세션과 메모 대기 세션의 강사
SET @instructor_2_email = 'sangeun4153@gmail.com'; -- 박상은 · 분석 중 세션과 실패 세션의 강사
SET @student_1_email = 'oganesson12@gmail.com'; -- 김태정
SET @student_2_email = 'yjhn0410@gmail.com'; -- 양지훈
SET @student_3_email = 'fishbread00@gmail.com'; -- 엄기훈


-- ============================================================================
-- 1. 시드 대역 정리 (FK 역순)
-- ============================================================================
-- WHERE 로 대역을 한정해 실제 데이터를 지우지 않는다. 대역을 변수로 두지 않고 리터럴로 반복해 적는 것은
-- 의도다 — 지우는 문장은 무엇을 지우는지가 그 줄에서 바로 보여야 한다.
--
-- 시드 세션에 실제 활동(메모 작성 등)이 붙으면 그 행은 대역 밖이라 여기서 지워지지 않고, FK 가 걸려
-- DELETE 가 실패한다. 그때는 scripts/clean-demo-seed.sql 로 수동 정리한다.
DELETE FROM `quiz_answers` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `quiz_options` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `quiz_questions` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `quizzes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `review_recommendations` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `student_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `instructor_report_tips` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `instructor_report_insights` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `instructor_report_scores` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `instructor_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `session_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `check_prompt_evidences` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `check_prompts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `coaching_history_response_counts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `coaching_histories` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `group_alert_response_counts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `group_alerts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `attention_events` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `interaction_events` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `chat_messages` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `recording_files` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `recordings` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `transcripts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `session_sections` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `instructor_notes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `pipeline_jobs` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `session_status_changes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `session_participants` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `sessions` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
DELETE FROM `members` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;


-- ============================================================================
-- 2. 회원 — 실제 계정을 먼저 찾고, 못 채운 자리만 가짜로 만든다
-- ============================================================================
-- 대역 정리 뒤에 찾는다. 앞선 실행이 만든 가짜 회원이 이메일로 잡히는 일을 없앤다.
SET @instructor_1_member_id = (
    SELECT `id` FROM `members` WHERE `email` = @instructor_1_email AND `deleted_at` IS NULL ORDER BY `id` LIMIT 1
);
SET @instructor_2_member_id = (
    SELECT `id` FROM `members` WHERE `email` = @instructor_2_email AND `deleted_at` IS NULL ORDER BY `id` LIMIT 1
);
SET @student_1_member_id = (
    SELECT `id` FROM `members` WHERE `email` = @student_1_email AND `deleted_at` IS NULL ORDER BY `id` LIMIT 1
);
SET @student_2_member_id = (
    SELECT `id` FROM `members` WHERE `email` = @student_2_email AND `deleted_at` IS NULL ORDER BY `id` LIMIT 1
);
SET @student_3_member_id = (
    SELECT `id` FROM `members` WHERE `email` = @student_3_email AND `deleted_at` IS NULL ORDER BY `id` LIMIT 1
);

-- 같은 계정이 두 자리에 들어가면 UK_SESSION_PARTICIPANTS_SESSION_MEMBER 에 걸린다. 변수 블록에 같은
-- 이메일을 두 번 적는 실수를 시드 실패가 아니라 "뒤쪽 자리는 가짜"로 흡수한다. 앞자리가 우선이다.
SET @instructor_2_member_id = IF(@instructor_2_member_id <=> @instructor_1_member_id, NULL, @instructor_2_member_id);
SET @student_1_member_id = IF(
    @student_1_member_id <=> @instructor_1_member_id OR @student_1_member_id <=> @instructor_2_member_id,
    NULL, @student_1_member_id);
SET @student_2_member_id = IF(
    @student_2_member_id <=> @instructor_1_member_id OR @student_2_member_id <=> @instructor_2_member_id
        OR @student_2_member_id <=> @student_1_member_id,
    NULL, @student_2_member_id);
SET @student_3_member_id = IF(
    @student_3_member_id <=> @instructor_1_member_id OR @student_3_member_id <=> @instructor_2_member_id
        OR @student_3_member_id <=> @student_1_member_id OR @student_3_member_id <=> @student_2_member_id,
    NULL, @student_3_member_id);

-- 못 찾은 자리만 가짜로 채운다. 1000000001901~1905 는 이 대체용 자리다 — 아래 가짜 학생 풀(1005~1024)과
-- 번호를 떼어 놓아야 참가자 ID 계산(참가자 = 3000 + (회원 - 1000))에 구멍이 생기지 않는다.
-- 이름을 사람 이름처럼 짓지 않는 것은 의도다. 화면에 '시연강사1' 이 보이면 매칭에 실패했다는 뜻이다.
INSERT INTO `members` (`id`, `google_subject`, `email`, `display_name`, `profile_image_url`, `created_at`, `updated_at`)
SELECT `id`, `google_subject`, `email`, `display_name`, NULL,
       '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'
FROM (
    SELECT 1000000001901 AS `id`, 'demo-subject-instructor-1' AS `google_subject`,
           'demo-instructor-1@zani.invalid' AS `email`, '시연강사1' AS `display_name`,
           @instructor_1_member_id AS `matched`
    UNION ALL SELECT 1000000001902, 'demo-subject-instructor-2', 'demo-instructor-2@zani.invalid', '시연강사2', @instructor_2_member_id
    UNION ALL SELECT 1000000001903, 'demo-subject-student-1', 'demo-student-1@zani.invalid', '시연학생1', @student_1_member_id
    UNION ALL SELECT 1000000001904, 'demo-subject-student-2', 'demo-student-2@zani.invalid', '시연학생2', @student_2_member_id
    UNION ALL SELECT 1000000001905, 'demo-subject-student-3', 'demo-student-3@zani.invalid', '시연학생3', @student_3_member_id
) `fallback`
WHERE `matched` IS NULL;

SET @instructor_1_member_id = COALESCE(@instructor_1_member_id, 1000000001901);
SET @instructor_2_member_id = COALESCE(@instructor_2_member_id, 1000000001902);
SET @student_1_member_id = COALESCE(@student_1_member_id, 1000000001903);
SET @student_2_member_id = COALESCE(@student_2_member_id, 1000000001904);
SET @student_3_member_id = COALESCE(@student_3_member_id, 1000000001905);

-- 나머지 20명. 풀세트 세션은 강사 1 + 학생 23 = 24명이고, 그중 넷이 위의 계정이다.
-- 이름은 fe/src/domains/lecture/presentation/fixtures.ts 의 participantsMeta 를 그대로 따른다 —
-- 프로토타입 화면과 같은 사람이 보여야 시연에서 두 화면을 나란히 놓을 수 있다.
-- 이메일 도메인이 .invalid 인 것은 의도다(RFC 2606). 리포트 알림 릴레이를 켠 환경에서도 이 주소로는
-- 메일이 나갈 수 없다 — 가짜 학생 23명에게 발송이 시도되는 사고를 도메인 수준에서 막는다.
INSERT INTO `members` (`id`, `google_subject`, `email`, `display_name`, `profile_image_url`, `created_at`, `updated_at`)
VALUES
    (1000000001005, 'demo-google-subject-05', 'demo05@zani.invalid', '강태오', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001006, 'demo-google-subject-06', 'demo06@zani.invalid', '윤서아', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001007, 'demo-google-subject-07', 'demo07@zani.invalid', '오지호', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001008, 'demo-google-subject-08', 'demo08@zani.invalid', '김도현', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001009, 'demo-google-subject-09', 'demo09@zani.invalid', '이서연', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001010, 'demo-google-subject-10', 'demo10@zani.invalid', '이준호', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001011, 'demo-google-subject-11', 'demo11@zani.invalid', '박지온', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001012, 'demo-google-subject-12', 'demo12@zani.invalid', '정민재', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001013, 'demo-google-subject-13', 'demo13@zani.invalid', '한수빈', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001014, 'demo-google-subject-14', 'demo14@zani.invalid', '강동현', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001015, 'demo-google-subject-15', 'demo15@zani.invalid', '최지우', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001016, 'demo-google-subject-16', 'demo16@zani.invalid', '문서연', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001017, 'demo-google-subject-17', 'demo17@zani.invalid', '임세훈', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001018, 'demo-google-subject-18', 'demo18@zani.invalid', '권민아', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001019, 'demo-google-subject-19', 'demo19@zani.invalid', '오지훈', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001020, 'demo-google-subject-20', 'demo20@zani.invalid', '김나영', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001021, 'demo-google-subject-21', 'demo21@zani.invalid', '문지후', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001022, 'demo-google-subject-22', 'demo22@zani.invalid', '서하늘', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001023, 'demo-google-subject-23', 'demo23@zani.invalid', '조현우', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000'),
    (1000000001024, 'demo-google-subject-24', 'demo24@zani.invalid', '배수연', NULL, '2026-07-01 00:00:00.000000', '2026-07-01 00:00:00.000000');


-- ============================================================================
-- 3. 세션 4건
-- ============================================================================
-- 완성 1건(풀세트)과 미완성 3건. 미완성 세 건은 분석 상태별 화면을 확인하는 용도라 참가자와 메모까지만 채운다.
--
-- 강사 두 사람에게 두 건씩 나눠 준다. 리포트가 있는 완성 세션은 한 건뿐이라 강사 1 에게 간다 —
-- 두 강사 모두에게 완성 세션을 주려면 참여도 1만 행짜리 세트를 하나 더 만들어야 하고, 그건 시연에
-- 필요한 것보다 크다. 완성 세션을 반대편 강사에게 넘기려면 아래 host_member_id 두 곳을 맞바꾸면 된다.
SET @s1 = 1000000002001; -- React 상태관리 심화 · ENDED/COMPLETED · 74분 · 24명 · 강사 1
SET @s2 = 1000000002002; -- Spring Boot REST API · ENDED/PROCESSING · 강사 2
SET @s3 = 1000000002003; -- 데이터베이스 정규화 · ENDED/WAITING_FOR_NOTE · 강사 1
SET @s4 = 1000000002004; -- Docker 실전 배포 · ENDED/FAILED · 강사 2

-- 사용자 변수는 스칼라만 담으므로 문자열로 둔다. DATETIME(6) 컬럼과 DATE_ADD 양쪽에서 그대로 해석된다.
SET @s1_started_at = '2026-07-14 01:00:00.000000';
SET @s1_ended_at = '2026-07-14 02:14:00.000000'; -- 74분 = 4,440,000ms

INSERT INTO `sessions` (`id`, `host_member_id`, `title`, `invite_code`, `status`, `analysis_status`,
                        `started_at`, `ended_at`, `note_due_at`, `created_at`, `updated_at`)
VALUES
    (@s1, @instructor_1_member_id, 'React 상태관리 심화', 'DEMO0001', 'ENDED', 'COMPLETED',
     @s1_started_at, @s1_ended_at, NULL, '2026-07-14 00:50:00.000000', '2026-07-14 03:05:00.000000'),
    (@s2, @instructor_2_member_id, 'Spring Boot REST API', 'DEMO0002', 'ENDED', 'PROCESSING',
     '2026-07-16 05:00:00.000000', '2026-07-16 07:05:00.000000', NULL,
     '2026-07-16 04:50:00.000000', '2026-07-16 07:40:00.000000'),
    -- 메모를 아직 확정하지 않은 세션. note_due_at 은 마지막 입력에서 30분 뒤다(FRD §16 자동 확정 기준).
    (@s3, @instructor_1_member_id, '데이터베이스 정규화', 'DEMO0003', 'ENDED', 'WAITING_FOR_NOTE',
     '2026-07-08 06:00:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 07:27:00.000000',
     '2026-07-08 05:50:00.000000', '2026-07-08 06:57:00.000000'),
    (@s4, @instructor_2_member_id, 'Docker 실전 배포', 'DEMO0004', 'ENDED', 'FAILED',
     '2026-07-13 02:00:00.000000', '2026-07-13 03:20:00.000000', NULL,
     '2026-07-13 01:50:00.000000', '2026-07-13 03:55:00.000000');

INSERT INTO `session_status_changes` (`id`, `session_id`, `from_status`, `to_status`, `changed_at`, `created_at`)
VALUES
    (1000000004001, @s1, NULL, 'LIVE', '2026-07-14 01:00:00.000000', '2026-07-14 01:00:00.000000'),
    (1000000004002, @s1, 'LIVE', 'ENDED', '2026-07-14 02:14:00.000000', '2026-07-14 02:14:00.000000'),
    (1000000004003, @s2, NULL, 'LIVE', '2026-07-16 05:00:00.000000', '2026-07-16 05:00:00.000000'),
    (1000000004004, @s2, 'LIVE', 'ENDED', '2026-07-16 07:05:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000004005, @s3, NULL, 'LIVE', '2026-07-08 06:00:00.000000', '2026-07-08 06:00:00.000000'),
    (1000000004006, @s3, 'LIVE', 'ENDED', '2026-07-08 06:47:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000004007, @s4, NULL, 'LIVE', '2026-07-13 02:00:00.000000', '2026-07-13 02:00:00.000000'),
    (1000000004008, @s4, 'LIVE', 'ENDED', '2026-07-13 03:20:00.000000', '2026-07-13 03:20:00.000000');


-- ============================================================================
-- 4. 참가자
-- ============================================================================
-- 풀세트 세션의 참가자 ID 는 1000000003001(강사) + 1000000003002~3024(학생 23명)로 고정한다.
-- 앞의 셋(3002~3004)이 실제 로그인 학생이고 나머지 스무 명이 가짜다.
-- 아래 블록들이 이 ID 를 상수로 참조한다 — 시드 안에서 참가자를 다시 조회하지 않아도 되게 하려는 것이다.
INSERT INTO `session_participants` (`id`, `session_id`, `member_id`, `role`, `first_joined_at`, `last_accessed_at`,
                                    `created_at`, `updated_at`)
VALUES
    (1000000003001, @s1, @instructor_1_member_id, 'INSTRUCTOR', '2026-07-14 00:55:00.000000', '2026-07-14 02:14:00.000000',
     '2026-07-14 00:55:00.000000', '2026-07-14 02:14:00.000000'),
    (1000000003002, @s1, @student_1_member_id, 'STUDENT', '2026-07-14 01:00:30.000000', '2026-07-14 02:14:00.000000',
     '2026-07-14 01:00:30.000000', '2026-07-14 02:14:00.000000'),
    (1000000003003, @s1, @student_2_member_id, 'STUDENT', '2026-07-14 01:00:35.000000', '2026-07-14 02:14:00.000000',
     '2026-07-14 01:00:35.000000', '2026-07-14 02:14:00.000000'),
    (1000000003004, @s1, @student_3_member_id, 'STUDENT', '2026-07-14 01:00:38.000000', '2026-07-14 02:14:00.000000',
     '2026-07-14 01:00:38.000000', '2026-07-14 02:14:00.000000');

-- 가짜 회원 20명은 회원 ID 에서 참가자 ID 를 계산해 넣는다(1000000001005 → 1000000003005).
INSERT INTO `session_participants` (`id`, `session_id`, `member_id`, `role`, `first_joined_at`, `last_accessed_at`,
                                    `created_at`, `updated_at`)
SELECT 1000000003000 + (m.`id` - 1000000001000), @s1, m.`id`, 'STUDENT',
       '2026-07-14 01:00:40.000000', '2026-07-14 02:14:00.000000',
       '2026-07-14 01:00:40.000000', '2026-07-14 02:14:00.000000'
FROM `members` m
WHERE m.`id` BETWEEN 1000000001005 AND 1000000001024;

-- 미완성 세션 3건은 강사 + 시연 학생 3명 + 가짜 1명으로 둔다. 목록 화면에 상태별로 뜨는 것이 목적이라
-- 더 필요하지 않다. 학생 세 사람 모두를 넣는 이유는 각자 자기 계정으로 로그인했을 때 네 가지 상태가
-- 모두 보여야 하기 때문이다.
INSERT INTO `session_participants` (`id`, `session_id`, `member_id`, `role`, `first_joined_at`, `last_accessed_at`,
                                    `created_at`, `updated_at`)
VALUES
    (1000000003101, @s2, @instructor_2_member_id, 'INSTRUCTOR', '2026-07-16 04:55:00.000000', '2026-07-16 07:05:00.000000', '2026-07-16 04:55:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000003102, @s2, @student_1_member_id, 'STUDENT', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000003103, @s2, @student_2_member_id, 'STUDENT', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000003104, @s2, @student_3_member_id, 'STUDENT', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000003105, @s2, 1000000001005, 'STUDENT', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000', '2026-07-16 05:01:00.000000', '2026-07-16 07:05:00.000000'),
    (1000000003201, @s3, @instructor_1_member_id, 'INSTRUCTOR', '2026-07-08 05:55:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 05:55:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000003202, @s3, @student_1_member_id, 'STUDENT', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000003203, @s3, @student_2_member_id, 'STUDENT', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000003204, @s3, @student_3_member_id, 'STUDENT', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000003205, @s3, 1000000001006, 'STUDENT', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000', '2026-07-08 06:01:00.000000', '2026-07-08 06:47:00.000000'),
    (1000000003301, @s4, @instructor_2_member_id, 'INSTRUCTOR', '2026-07-13 01:55:00.000000', '2026-07-13 03:20:00.000000', '2026-07-13 01:55:00.000000', '2026-07-13 03:20:00.000000'),
    (1000000003302, @s4, @student_1_member_id, 'STUDENT', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000'),
    (1000000003303, @s4, @student_2_member_id, 'STUDENT', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000'),
    (1000000003304, @s4, @student_3_member_id, 'STUDENT', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000'),
    (1000000003305, @s4, 1000000001007, 'STUDENT', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000', '2026-07-13 02:01:00.000000', '2026-07-13 03:20:00.000000');


-- ============================================================================
-- 5. 수업 내용 구간 · 전사 · 메모 · 사후 처리 작업
-- ============================================================================
INSERT INTO `session_sections` (`id`, `session_id`, `title`, `summary`, `started_offset_ms`, `ended_offset_ms`,
                                `created_at`, `updated_at`)
VALUES
    (1000000005001, @s1, '상태 관리 개요', 'useState 의 역할과 props drilling 의 한계를 소개한 도입 구간이다.', 0, 519000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005002, @s1, 'Context 와 리렌더링', 'Context 구독과 리렌더링 원리를 다뤘다. 이해 확인이 가장 많이 몰린 구간이다.', 520000, 921000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005003, @s1, '질문 · Context 리렌더', '학생 질문이 이어지며 확인이 필요했던 구간이다.', 922000, 1439000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005004, @s1, 'useMemo 메모이제이션', '반복된 확인 필요 신호가 감지된 핵심 구간이다.', 1440000, 1859000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005005, @s1, '상태관리 라이브러리 비교', '라이브러리 선택 기준을 다룬 안정 구간이다.', 1860000, 2519000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005006, @s1, 'Zustand 실습', '실습으로 개념을 굳힌 구간이다. 참여도가 가장 높았다.', 2520000, 3079000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000005007, @s1, '정리와 질문', '핵심 개념을 정리하고 마무리한 구간이다.', 3080000, 4440000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000');

-- transcript_document 의 구조는 아직 계약 문서가 없다. 전사 파이프라인(후행 일감)이 정본을 정하기 전까지
-- 리포트 화면이 읽을 최소 형태(화자 + 구간 + 문장)로 둔다. 형태가 정해지면 이 시드도 함께 고친다.
INSERT INTO `transcripts` (`id`, `session_id`, `transcript_document`, `created_at`, `updated_at`)
VALUES (1000000006001, @s1, CAST('{
  "version": 1,
  "language": "ko",
  "segments": [
    {"startedOffsetMs": 2000, "endedOffsetMs": 32000, "speaker": "박서준", "text": "자, 오늘은 React의 상태 관리를 깊이 있게 다뤄보겠습니다."},
    {"startedOffsetMs": 195000, "endedOffsetMs": 226000, "speaker": "박서준", "text": "useState는 지역 상태에 적합하지만 전역 상태는 다른 접근이 필요해요."},
    {"startedOffsetMs": 400000, "endedOffsetMs": 431000, "speaker": "박서준", "text": "상태를 여러 단계로 내려주다 보면 props drilling 문제가 생깁니다."},
    {"startedOffsetMs": 520000, "endedOffsetMs": 551000, "speaker": "박서준", "text": "먼저 Context API의 리렌더링 이슈를 이해해야 합니다."},
    {"startedOffsetMs": 730000, "endedOffsetMs": 745000, "speaker": "정하윤", "text": "Context랑 Redux는 어떤 기준으로 골라야 하나요?"},
    {"startedOffsetMs": 755000, "endedOffsetMs": 790000, "speaker": "박서준", "text": "전역성이 크고 미들웨어가 필요하면 라이브러리, 아니면 Context가 낫습니다."},
    {"startedOffsetMs": 922000, "endedOffsetMs": 940000, "speaker": "김도현", "text": "선생님, Context 값이 바뀌면 왜 하위 전체가 리렌더되나요?"},
    {"startedOffsetMs": 948000, "endedOffsetMs": 980000, "speaker": "박서준", "text": "좋은 질문이에요. Provider value의 참조가 바뀌기 때문입니다."},
    {"startedOffsetMs": 1145000, "endedOffsetMs": 1180000, "speaker": "박서준", "text": "예제 코드로 리렌더가 어디서 발생하는지 확인해볼게요."},
    {"startedOffsetMs": 1450000, "endedOffsetMs": 1490000, "speaker": "박서준", "text": "그래서 useMemo로 value를 메모이즈하는 패턴이 나옵니다."},
    {"startedOffsetMs": 1650000, "endedOffsetMs": 1662000, "speaker": "이지은", "text": "useCallback도 같이 써야 하나요?"},
    {"startedOffsetMs": 1672000, "endedOffsetMs": 1705000, "speaker": "박서준", "text": "함수를 props로 넘길 때만 필요하니 상황에 맞게 쓰면 됩니다."},
    {"startedOffsetMs": 1865000, "endedOffsetMs": 1900000, "speaker": "박서준", "text": "다음으로 외부 상태 관리 라이브러리를 비교해볼게요."},
    {"startedOffsetMs": 2520000, "endedOffsetMs": 2560000, "speaker": "박서준", "text": "Zustand로 같은 예제를 다시 구현하면 훨씬 간결해집니다."},
    {"startedOffsetMs": 3080000, "endedOffsetMs": 3125000, "speaker": "박서준", "text": "정리하고 질문 받겠습니다. 오늘 자료는 리포트에 함께 올려둘게요."}
  ]
}' AS JSON), '2026-07-14 02:40:00.000000', '2026-07-14 02:40:00.000000');

INSERT INTO `instructor_notes` (`id`, `session_id`, `instructor_participant_id`, `content`, `status`,
                                `last_edited_at`, `finalized_at`, `created_at`, `updated_at`)
VALUES
    (1000000007001, @s1, 1000000003001,
     'Context 리렌더링 구간에서 질문이 몰렸다. 다음 수업에서는 예제를 먼저 보여주고 원리를 설명하는 순서로 바꿔보자. Zustand 실습은 반응이 좋았다.',
     'FINALIZED', '2026-07-14 02:25:00.000000', '2026-07-14 02:30:00.000000',
     '2026-07-14 02:20:00.000000', '2026-07-14 02:30:00.000000'),
    (1000000007002, @s2, 1000000003101,
     '예외 처리 구간 설명이 길어졌다. 응답 코드 표를 미리 배포하자.',
     'FINALIZED', '2026-07-16 07:20:00.000000', '2026-07-16 07:25:00.000000',
     '2026-07-16 07:15:00.000000', '2026-07-16 07:25:00.000000'),
    -- 확정 전이라 사후 처리가 시작되지 않았다. 세션의 analysis_status 가 WAITING_FOR_NOTE 인 이유다.
    (1000000007003, @s3, 1000000003201,
     '정규형 예시를 더 쉬운 도메인으로 바꿔야겠다. 아직 작성 중.',
     'DRAFT', '2026-07-08 06:57:00.000000', NULL,
     '2026-07-08 06:50:00.000000', '2026-07-08 06:57:00.000000'),
    (1000000007004, @s4, 1000000003301,
     '배포 실습에서 네트워크 문제로 시간이 밀렸다.',
     'FINALIZED', '2026-07-13 03:35:00.000000', '2026-07-13 03:40:00.000000',
     '2026-07-13 03:30:00.000000', '2026-07-13 03:40:00.000000');

-- 사후 처리 작업은 메모 확정 시각에 만들어진다(V6 주석). 확정하지 않은 @s3 에는 행이 없다.
INSERT INTO `pipeline_jobs` (`id`, `session_id`, `status`, `created_at`, `updated_at`)
VALUES
    (1000000008001, @s1, 'PUBLISHED', '2026-07-14 02:30:00.000000', '2026-07-14 03:05:00.000000'),
    (1000000008002, @s2, 'ANALYZING', '2026-07-16 07:25:00.000000', '2026-07-16 07:40:00.000000'),
    (1000000008003, @s4, 'FAILED', '2026-07-13 03:40:00.000000', '2026-07-13 03:55:00.000000');


-- ============================================================================
-- 6. 녹화 — 트랙 Egress 한 건당 recordings 한 행, 그 아래 파일 한 행
-- ============================================================================
-- 실제 운영도 트랙당 Egress 를 따로 띄우고(Recording.startTrack) 종료 webhook 에서 파일 행을 남긴다.
-- 마이크 24개 + 강사 화면 공유 영상·오디오 2개 = 26개다.
-- storage_key 는 문자열만 넣는다. 실제 파일이 없어 영상 재생과 클립 시연은 되지 않는다.
INSERT INTO `recordings` (`id`, `session_id`, `livekit_egress_id`, `recording_type`, `attempt_number`, `status`,
                          `started_at`, `ended_at`, `created_at`, `updated_at`)
SELECT 1000000009000 + (p.`id` - 1000000003000), @s1,
       CONCAT('EG_demo_track_', LPAD(p.`id` - 1000000003000, 4, '0')), 'TRACK', 1, 'COMPLETE',
       @s1_started_at, @s1_ended_at, @s1_started_at, @s1_ended_at
FROM `session_participants` p
WHERE p.`session_id` = @s1
UNION ALL
SELECT 1000000009025, @s1, 'EG_demo_track_0025', 'TRACK', 1, 'COMPLETE', @s1_started_at, @s1_ended_at, @s1_started_at, @s1_ended_at
UNION ALL
SELECT 1000000009026, @s1, 'EG_demo_track_0026', 'TRACK', 1, 'COMPLETE', @s1_started_at, @s1_ended_at, @s1_started_at, @s1_ended_at;

-- file_type 은 TRACK 하나뿐이다. V1 주석의 HLS·COMPOSITE·AUDIO 는 쓰이지 않는다(RecordingFile.TYPE_TRACK).
INSERT INTO `recording_files` (`id`, `session_id`, `recording_id`, `session_participant_id`, `file_type`,
                               `storage_key`, `livekit_track_sid`, `started_offset_ms`, `ended_offset_ms`, `created_at`)
SELECT 1000000010000 + (p.`id` - 1000000003000), @s1, 1000000009000 + (p.`id` - 1000000003000), p.`id`, 'TRACK',
       CONCAT(
           IF(p.`role` = 'INSTRUCTOR',
              'raw/instructor/instructor-microphone-TR_demo_',
              CONCAT('raw/participants/student-', LPAD(p.`id` - 1000000003001, 3, '0'),
                     '/student-', LPAD(p.`id` - 1000000003001, 3, '0'), '-microphone-TR_demo_')),
           LPAD(p.`id` - 1000000003000, 4, '0')),
       CONCAT('TR_demo_', LPAD(p.`id` - 1000000003000, 4, '0')), 0, 4440000, @s1_ended_at
FROM `session_participants` p
WHERE p.`session_id` = @s1
UNION ALL
SELECT 1000000010025, @s1, 1000000009025, 1000000003001, 'TRACK',
       'raw/instructor/instructor-screen-share-TR_demo_0025', 'TR_demo_0025', 1140000, 3080000, @s1_ended_at
UNION ALL
SELECT 1000000010026, @s1, 1000000009026, 1000000003001, 'TRACK',
       'raw/instructor/instructor-screen-share-audio-TR_demo_0026', 'TR_demo_0026', 1140000, 3080000, @s1_ended_at;


-- ============================================================================
-- 7. 참여도 이벤트 — 10초 × 444틱 × 학생 23명 = 10,212행
-- ============================================================================
-- 구간별 점수는 CASE 로 설계한다. 8~15분 저참여, 42~51분 실습 고참여, 나머지 중간이다.
-- 20건에 1건(5%)은 CAMERA_OFF·UNMEASURABLE 로 두어 attention_score 가 NULL 인 경로도 남긴다.
-- CAMERA_OFF 는 보는 순간 확정돼 판정 창이 없고(§4.2), UNMEASURABLE 은 창을 봐야 정해져 창을 갖는다.
INSERT INTO `attention_events` (`id`, `session_id`, `session_participant_id`, `detector_outcome`, `attention_score`,
                                `occurred_offset_ms`, `window_started_offset_ms`, `low_engagement`,
                                `confidence`, `signal_quality`, `feature_schema_version`, `engine_version`,
                                `client_event_id`, `created_at`)
WITH RECURSIVE `tick` (`n`) AS (
    SELECT 0
    UNION ALL
    SELECT `n` + 1 FROM `tick` WHERE `n` < 443
),
`student` AS (
    -- idx 는 1~23 이어야 한다. 아래 id 계산(n * 23 + idx)이 그 가정 위에서만 충돌하지 않기 때문에
    -- 참가자 ID 로 빼서 만들지 않고 ROW_NUMBER 로 센다 — 참가자 번호에 구멍이 생겨도 안전하다.
    SELECT `id` AS `participant_id`, ROW_NUMBER() OVER (ORDER BY `id`) AS `idx`
    FROM `session_participants`
    WHERE `session_id` = @s1 AND `role` = 'STUDENT'
),
`sample` AS (
    SELECT t.`n`,
           s.`participant_id`,
           s.`idx`,
           (t.`n` + 1) * 10000 AS `occurred_offset_ms`,
           t.`n` * 10000 AS `window_started_offset_ms`,
           (t.`n` * 7 + s.`idx`) % 20 AS `unmeasurable_slot`,
           (t.`n` + s.`idx`) % 2 AS `variant`,
           -- 측정 불가일 때 CAMERA_OFF 와 UNMEASURABLE 을 가른다. variant 를 재사용하면 안 된다 —
           -- unmeasurable_slot 이 0 이면 7n + idx 가 짝수라 variant 가 항상 0 이 되어 한쪽만 나온다.
           s.`idx` % 2 AS `unmeasurable_kind`
    FROM `tick` t
    CROSS JOIN `student` s
),
`scored` AS (
    SELECT sa.*,
           CASE
               WHEN sa.`unmeasurable_slot` = 0 THEN NULL
               WHEN sa.`occurred_offset_ms` >= 480000 AND sa.`occurred_offset_ms` < 900000 THEN 1 + sa.`variant`
               WHEN sa.`occurred_offset_ms` >= 2520000 AND sa.`occurred_offset_ms` < 3080000 THEN 3 + sa.`variant`
               ELSE 2 + sa.`variant`
           END AS `attention_score`
    FROM `sample` sa
)
SELECT 1000000100000 + `n` * 23 + `idx`,
       @s1,
       `participant_id`,
       CASE
           WHEN `attention_score` IS NULL THEN IF(`unmeasurable_kind` = 0, 'CAMERA_OFF', 'UNMEASURABLE')
           ELSE ELT(`attention_score`, 'NOT_ENGAGED', 'BARELY_ENGAGED', 'ENGAGED', 'HIGHLY_ENGAGED')
       END,
       `attention_score`,
       `occurred_offset_ms`,
       IF(`attention_score` IS NULL AND `unmeasurable_kind` = 0, NULL, `window_started_offset_ms`),
       IF(`attention_score` IS NULL, NULL, `attention_score` <= 2),
       IF(`attention_score` IS NULL, NULL, 0.6000 + `attention_score` * 0.0800),
       CASE
           WHEN `attention_score` IS NOT NULL THEN 0.7000 + `attention_score` * 0.0500
           WHEN `unmeasurable_kind` = 0 THEN NULL -- CAMERA_OFF: 볼 영상이 없어 신호 품질도 없다
           ELSE 0.3500 -- UNMEASURABLE: 영상은 있는데 얼굴 특징을 못 뽑았다
       END,
       'mediapipe_98_v1',
       'onnxruntime-web-1.19.2',
       CONCAT('demo-', `idx`, '-', `n`),
       DATE_ADD(@s1_started_at, INTERVAL `occurred_offset_ms` DIV 1000 SECOND)
FROM `scored`;


-- ============================================================================
-- 8. 이해 확인 프롬프트와 근거
-- ============================================================================
-- 저참여 이벤트에서 뽑는다. 110초 버킷마다 한 건씩 골라 수업 전체에 흩뿌리고, 버킷마다 다른 학생이 뽑히도록
-- 순번을 버킷 번호로 돌린다. 실습 구간(42~51분)은 저참여 이벤트가 거의 없어 자연스럽게 프롬프트가 비는데,
-- 그 편이 실제 수업에 가깝다. 그래서 총 건수는 40건 안팎으로 정확히 고정되지 않는다.
INSERT INTO `check_prompts` (`id`, `session_id`, `session_participant_id`, `trigger_type`, `status`, `response`,
                             `shown_offset_ms`, `responded_offset_ms`, `created_at`, `updated_at`)
SELECT 1000000011000 + `rn`,
       @s1,
       `session_participant_id`,
       'UNDERSTANDING_CHECK', -- 서버가 기록하는 프롬프트는 이것뿐이다(V5)
       -- 무응답은 TIMEOUT 으로 남고 응답 시각을 갖지 않는다(CheckPrompt.respond).
       IF(`rn` % 5 = 0, 'TIMEOUT', 'RESPONDED'),
       CASE `rn` % 5
           WHEN 0 THEN 'NON_RESPONSE'
           WHEN 1 THEN 'CONFUSED'
           WHEN 2 THEN 'MISSED'
           ELSE 'OK'
       END,
       `shown_offset_ms`,
       IF(`rn` % 5 = 0, NULL, `shown_offset_ms` + 4000 + (`rn` % 7) * 1000),
       DATE_ADD(@s1_started_at, INTERVAL `shown_offset_ms` DIV 1000 SECOND),
       DATE_ADD(@s1_started_at, INTERVAL `shown_offset_ms` DIV 1000 SECOND)
FROM (
    SELECT `session_participant_id`,
           `occurred_offset_ms` AS `shown_offset_ms`,
           ROW_NUMBER() OVER (ORDER BY `id`) AS `rn`
    FROM (
        SELECT ae.`id`,
               ae.`session_participant_id`,
               ae.`occurred_offset_ms`,
               ae.`occurred_offset_ms` DIV 110000 AS `bucket`,
               ROW_NUMBER() OVER (PARTITION BY ae.`occurred_offset_ms` DIV 110000 ORDER BY ae.`id`) AS `pick`
        FROM `attention_events` ae
        WHERE ae.`session_id` = @s1 AND ae.`low_engagement` = 1
    ) `ranked`
    WHERE `ranked`.`pick` = 1 + (`ranked`.`bucket` % 7)
) `picked`;

-- 근거는 프롬프트를 띄우게 만든 참여도 이벤트다. (참가자, 발생 시각)이 그 이벤트를 유일하게 가리킨다.
-- attention_event_id 와 interaction_event_id 중 정확히 하나만 채운다(CK_..._EXACTLY_ONE_SOURCE).
INSERT INTO `check_prompt_evidences` (`id`, `check_prompt_id`, `attention_event_id`, `interaction_event_id`,
                                      `evidence_type`, `created_at`)
SELECT 1000000012000 + (cp.`id` - 1000000011000), cp.`id`, ae.`id`, NULL, 'LOW_ENGAGEMENT_WINDOW', cp.`created_at`
FROM `check_prompts` cp
JOIN `attention_events` ae
    ON ae.`session_id` = cp.`session_id`
   AND ae.`session_participant_id` = cp.`session_participant_id`
   AND ae.`occurred_offset_ms` = cp.`shown_offset_ms`
WHERE cp.`session_id` = @s1;


-- ============================================================================
-- 9. 집단 알림과 응답 분포
-- ============================================================================
-- alert_type 은 팁 유형 5종(CoachingTipType)과 같은 이름을 쓴다. 알림이 곧 "가장 강한 유형"의 통지다.
-- 집계 창은 슬라이딩 5분(§7.5)이라 window_started = occurred - 300000 이다.
INSERT INTO `group_alerts` (`id`, `session_id`, `alert_type`, `window_started_offset_ms`, `window_ended_offset_ms`,
                            `numerator_count`, `denominator_count`, `occurred_offset_ms`, `created_at`)
VALUES
    (1000000013001, @s1, 'CONFUSED', 260000, 560000, 8, 23, 560000, '2026-07-14 01:09:20.000000'),
    (1000000013002, @s1, 'CONFUSED_AND_MISSED', 560000, 860000, 12, 23, 860000, '2026-07-14 01:14:20.000000'),
    (1000000013003, @s1, 'MISSED', 900000, 1200000, 9, 23, 1200000, '2026-07-14 01:20:00.000000'),
    (1000000013004, @s1, 'CONFUSED', 1250000, 1550000, 10, 23, 1550000, '2026-07-14 01:25:50.000000'),
    (1000000013005, @s1, 'NON_RESPONSE', 1600000, 1900000, 7, 23, 1900000, '2026-07-14 01:31:40.000000'),
    (1000000013006, @s1, 'UNMEASURABLE', 2000000, 2300000, 7, 23, 2300000, '2026-07-14 01:38:20.000000'),
    (1000000013007, @s1, 'MISSED', 3400000, 3700000, 8, 23, 3700000, '2026-07-14 02:01:40.000000');

-- 알림 하나당 네 가지 응답으로 분해한다. 네 값의 합이 분모(23)와 같아지도록 OK 를 나머지로 둔다.
-- 응답 이름은 프롬프트 응답 정본(PromptAnswer)을 따른다 — V1 주석의 NO_RESPONSE 는 V5 에서 NON_RESPONSE 로 정리됐다.
INSERT INTO `group_alert_response_counts` (`id`, `group_alert_id`, `response_type`, `response_count`, `created_at`)
SELECT 1000000014000 + (ga.`id` - 1000000013000) * 10 + r.`ord`,
       ga.`id`,
       r.`response_type`,
       CASE r.`response_type`
           WHEN 'CONFUSED' THEN FLOOR(ga.`numerator_count` * 0.5)
           WHEN 'MISSED' THEN FLOOR(ga.`numerator_count` * 0.3)
           WHEN 'NON_RESPONSE' THEN ga.`numerator_count` - FLOOR(ga.`numerator_count` * 0.5) - FLOOR(ga.`numerator_count` * 0.3)
           ELSE ga.`denominator_count` - ga.`numerator_count`
       END,
       ga.`created_at`
FROM `group_alerts` ga
CROSS JOIN (
    SELECT 1 AS `ord`, 'OK' AS `response_type`
    UNION ALL SELECT 2, 'CONFUSED'
    UNION ALL SELECT 3, 'MISSED'
    UNION ALL SELECT 4, 'NON_RESPONSE'
) r
WHERE ga.`session_id` = @s1;


-- ============================================================================
-- 10. 코칭 이력과 응답 분포
-- ============================================================================
-- 집단 알림 7건에 대응한다. 다섯 건은 팁을 보냈고 두 건은 못 보냈다(CK_COACHING_HISTORIES_OUTCOME 이
-- 두 경우의 컬럼 조합을 강제한다). 팁 문구는 .agents/attention-coaching-context.md §8 고정 템플릿이다.
INSERT INTO `coaching_histories` (`id`, `session_id`, `trigger_id`, `triggered_at`, `completed_at`,
                                  `denominator_count`, `selected_tip_type`, `outcome_status`, `transcript_status`,
                                  `transcript_started_at`, `transcript_ended_at`, `topic`,
                                  `tip_type`, `tip_title`, `tip_message`, `unavailable_reason`, `created_at`)
VALUES
    (1000000015001, @s1, 'demo-trigger-01', '2026-07-14 01:09:20.000000', '2026-07-14 01:09:42.000000', 23,
     'CONFUSED', 'TIP_DELIVERED', 'TRANSCRIBED', '2026-07-14 01:04:20.000000', '2026-07-14 01:09:20.000000',
     'Context API 의 리렌더링 조건', 'CONFUSED', '추가 설명이 필요해요',
     '전체 학생의 35%가 현재 내용을 헷갈려 하고 있어요. Context 리렌더링을 다른 예시로 다시 설명해 주세요.', NULL,
     '2026-07-14 01:09:42.000000'),
    (1000000015002, @s1, 'demo-trigger-02', '2026-07-14 01:14:20.000000', '2026-07-14 01:14:45.000000', 23,
     'CONFUSED_AND_MISSED', 'TIP_DELIVERED', 'TRANSCRIBED', '2026-07-14 01:09:20.000000', '2026-07-14 01:14:20.000000',
     'Provider value 참조와 메모이제이션', 'CONFUSED_AND_MISSED', '수업 흐름을 점검해 주세요',
     '전체 학생의 52%가 현재 수업을 따라가는 데 어려움을 겪고 있어요. 헷갈려요 30% · 놓쳤어요 22% 설명 속도를 낮추고 리렌더링 조건을 다시 정리해 주세요.', NULL,
     '2026-07-14 01:14:45.000000'),
    (1000000015003, @s1, 'demo-trigger-03', '2026-07-14 01:20:00.000000', '2026-07-14 01:20:21.000000', 23,
     'MISSED', 'TIP_DELIVERED', 'TRANSCRIBED', '2026-07-14 01:15:00.000000', '2026-07-14 01:20:00.000000',
     '리렌더 발생 지점 예제', 'MISSED', '내용을 다시 짚어주세요',
     '전체 학생의 39%가 방금 설명을 놓쳤어요. 예제에서 리렌더가 발생한 지점을 짧게 요약한 뒤 수업을 이어가 주세요.', NULL,
     '2026-07-14 01:20:21.000000'),
    -- 전사는 됐지만 팁 문구 생성이 실패한 경우.
    (1000000015004, @s1, 'demo-trigger-04', '2026-07-14 01:25:50.000000', '2026-07-14 01:26:14.000000', 23,
     'CONFUSED', 'TIP_UNAVAILABLE', 'TRANSCRIBED', '2026-07-14 01:20:50.000000', '2026-07-14 01:25:50.000000',
     'useMemo 의존성 배열', NULL, NULL, NULL, 'TIP_GENERATION_FAILED',
     '2026-07-14 01:26:14.000000'),
    (1000000015005, @s1, 'demo-trigger-05', '2026-07-14 01:31:40.000000', '2026-07-14 01:32:03.000000', 23,
     'NON_RESPONSE', 'TIP_DELIVERED', 'TRANSCRIBED', '2026-07-14 01:26:40.000000', '2026-07-14 01:31:40.000000',
     '상태관리 라이브러리 선택 기준', 'NON_RESPONSE', '학생 반응을 확인해 주세요',
     '전체 학생의 30%가 질문에 응답하지 않았어요. 간단한 질문을 통해 학생들의 참여 상태를 확인해 주세요.', NULL,
     '2026-07-14 01:32:03.000000'),
    -- 강사 오디오가 없어 전사를 시도하지 못한 경우.
    (1000000015006, @s1, 'demo-trigger-06', '2026-07-14 01:38:20.000000', '2026-07-14 01:38:31.000000', 23,
     'UNMEASURABLE', 'TIP_UNAVAILABLE', 'NO_TRANSCRIPT', NULL, NULL, NULL,
     NULL, NULL, NULL, 'NO_TRANSCRIPT',
     '2026-07-14 01:38:31.000000'),
    (1000000015007, @s1, 'demo-trigger-07', '2026-07-14 02:01:40.000000', '2026-07-14 02:02:02.000000', 23,
     'MISSED', 'TIP_DELIVERED', 'TRANSCRIBED', '2026-07-14 01:56:40.000000', '2026-07-14 02:01:40.000000',
     'Zustand 스토어 구조 정리', 'MISSED', '내용을 다시 짚어주세요',
     '전체 학생의 34%가 방금 설명을 놓쳤어요. 스토어 구조를 짧게 요약한 뒤 수업을 이어가 주세요.', NULL,
     '2026-07-14 02:02:02.000000');

-- 코칭 쪽 분포는 다섯 종이다(V7: SIGNIFICANT, CONFUSED, MISSED, NON_RESPONSE, UNMEASURABLE).
-- SIGNIFICANT 는 "유의 상태로 잡힌 학생 수"이고 나머지 넷의 합과 같게 둔다.
INSERT INTO `coaching_history_response_counts` (`id`, `coaching_history_id`, `response_type`, `response_count`, `created_at`)
SELECT 1000000016000 + (ch.`id` - 1000000015000) * 10 + r.`ord`,
       ch.`id`,
       r.`response_type`,
       CASE r.`response_type`
           WHEN 'SIGNIFICANT' THEN 7 + (ch.`id` - 1000000015000) % 5
           WHEN 'CONFUSED' THEN 3 + (ch.`id` - 1000000015000) % 3
           WHEN 'MISSED' THEN 2 + (ch.`id` - 1000000015000) % 2
           WHEN 'NON_RESPONSE' THEN 1 + (ch.`id` - 1000000015000) % 2
           ELSE 1
       END,
       ch.`created_at`
FROM `coaching_histories` ch
CROSS JOIN (
    SELECT 1 AS `ord`, 'SIGNIFICANT' AS `response_type`
    UNION ALL SELECT 2, 'CONFUSED'
    UNION ALL SELECT 3, 'MISSED'
    UNION ALL SELECT 4, 'NON_RESPONSE'
    UNION ALL SELECT 5, 'UNMEASURABLE'
) r
WHERE ch.`session_id` = @s1;


-- ============================================================================
-- 11. 채팅
-- ============================================================================
-- 공개 채팅 위주에 비공개 질문 네 건을 섞는다. 비공개는 학생이 강사에게만 보낸 것이다.
INSERT INTO `chat_messages` (`id`, `session_id`, `sender_participant_id`, `recipient_participant_id`, `channel_type`,
                             `content`, `occurred_offset_ms`, `created_at`)
VALUES
    (1000000017001, @s1, 1000000003001, NULL, 'PUBLIC', '자료는 수업 끝나고 리포트에 올려둘게요.', 30000, '2026-07-14 01:00:30.000000'),
    (1000000017002, @s1, 1000000003004, NULL, 'PUBLIC', '소리 잘 들립니다!', 45000, '2026-07-14 01:00:45.000000'),
    (1000000017003, @s1, 1000000003007, NULL, 'PUBLIC', '화면 조금만 키워주실 수 있나요?', 120000, '2026-07-14 01:02:00.000000'),
    (1000000017004, @s1, 1000000003001, NULL, 'PUBLIC', '키웠습니다. 이 정도면 괜찮을까요?', 150000, '2026-07-14 01:02:30.000000'),
    (1000000017005, @s1, 1000000003012, NULL, 'PUBLIC', '네 잘 보여요', 165000, '2026-07-14 01:02:45.000000'),
    (1000000017006, @s1, 1000000003002, NULL, 'PUBLIC', 'props drilling 예시 한 번만 다시 보여주세요.', 420000, '2026-07-14 01:07:00.000000'),
    (1000000017007, @s1, 1000000003001, NULL, 'PUBLIC', '바로 앞 슬라이드로 돌아갈게요.', 445000, '2026-07-14 01:07:25.000000'),
    (1000000017008, @s1, 1000000003009, NULL, 'PUBLIC', 'Context를 쓰면 그 문제가 사라지는 건가요?', 560000, '2026-07-14 01:09:20.000000'),
    (1000000017009, @s1, 1000000003001, NULL, 'PUBLIC', '전달 자체는 사라지는데 리렌더 비용이 새로 생깁니다.', 590000, '2026-07-14 01:09:50.000000'),
    (1000000017010, @s1, 1000000003015, NULL, 'PUBLIC', '아 그래서 useMemo가 나오는군요', 610000, '2026-07-14 01:10:10.000000'),
    (1000000017011, @s1, 1000000003002, 1000000003001, 'PRIVATE', '선생님, 지금 구간이 잘 이해가 안 돼요.', 640000, '2026-07-14 01:10:40.000000'),
    (1000000017012, @s1, 1000000003001, 1000000003002, 'PRIVATE', '조금 뒤에 예제로 다시 짚어드릴게요.', 680000, '2026-07-14 01:11:20.000000'),
    (1000000017013, @s1, 1000000003018, NULL, 'PUBLIC', '리렌더가 되는지 어떻게 확인하나요?', 720000, '2026-07-14 01:12:00.000000'),
    (1000000017014, @s1, 1000000003001, NULL, 'PUBLIC', 'React DevTools의 Profiler로 확인합니다.', 760000, '2026-07-14 01:12:40.000000'),
    (1000000017015, @s1, 1000000003006, NULL, 'PUBLIC', '감사합니다', 800000, '2026-07-14 01:13:20.000000'),
    (1000000017016, @s1, 1000000003021, NULL, 'PUBLIC', 'Redux는 언제 쓰는 게 좋을까요?', 880000, '2026-07-14 01:14:40.000000'),
    (1000000017017, @s1, 1000000003001, NULL, 'PUBLIC', '미들웨어가 필요할 때요. 뒤에서 비교하겠습니다.', 910000, '2026-07-14 01:15:10.000000'),
    (1000000017018, @s1, 1000000003008, NULL, 'PUBLIC', 'Provider 값이 바뀌면 하위 전체가 리렌더되나요?', 930000, '2026-07-14 01:15:30.000000'),
    (1000000017019, @s1, 1000000003001, NULL, 'PUBLIC', '구독하는 컴포넌트가 모두 리렌더됩니다.', 975000, '2026-07-14 01:16:15.000000'),
    (1000000017020, @s1, 1000000003011, NULL, 'PUBLIC', '오 이제 이해했어요', 1010000, '2026-07-14 01:16:50.000000'),
    (1000000017021, @s1, 1000000003014, 1000000003001, 'PRIVATE', '앞부분을 놓쳤는데 다시 들을 수 있을까요?', 1080000, '2026-07-14 01:18:00.000000'),
    (1000000017022, @s1, 1000000003001, 1000000003014, 'PRIVATE', '녹화가 남으니 리포트에서 그 구간을 보실 수 있어요.', 1120000, '2026-07-14 01:18:40.000000'),
    (1000000017023, @s1, 1000000003003, NULL, 'PUBLIC', '예제 코드 링크 공유 가능할까요?', 1220000, '2026-07-14 01:20:20.000000'),
    (1000000017024, @s1, 1000000003001, NULL, 'PUBLIC', '리포트에 함께 올리겠습니다.', 1250000, '2026-07-14 01:20:50.000000'),
    (1000000017025, @s1, 1000000003017, NULL, 'PUBLIC', 'useMemo 의존성 배열에 뭘 넣어야 하나요?', 1480000, '2026-07-14 01:24:40.000000'),
    (1000000017026, @s1, 1000000003001, NULL, 'PUBLIC', 'value가 실제로 참조하는 값만 넣습니다.', 1520000, '2026-07-14 01:25:20.000000'),
    (1000000017027, @s1, 1000000003023, NULL, 'PUBLIC', '함수도 넣어야 하나요?', 1560000, '2026-07-14 01:26:00.000000'),
    (1000000017028, @s1, 1000000003001, NULL, 'PUBLIC', '함수는 useCallback으로 참조를 고정한 뒤 넣습니다.', 1610000, '2026-07-14 01:26:50.000000'),
    (1000000017029, @s1, 1000000003002, NULL, 'PUBLIC', 'useCallback을 항상 써야 하는 건 아니군요?', 1660000, '2026-07-14 01:27:40.000000'),
    (1000000017030, @s1, 1000000003001, NULL, 'PUBLIC', '네, props로 넘길 때만 필요합니다.', 1700000, '2026-07-14 01:28:20.000000'),
    (1000000017031, @s1, 1000000003020, NULL, 'PUBLIC', 'Zustand는 보일러플레이트가 적다고 들었어요', 1900000, '2026-07-14 01:31:40.000000'),
    (1000000017032, @s1, 1000000003001, NULL, 'PUBLIC', '맞습니다. 곧 같은 예제로 보여드릴게요.', 1950000, '2026-07-14 01:32:30.000000'),
    (1000000017033, @s1, 1000000003005, NULL, 'PUBLIC', '기대됩니다', 2000000, '2026-07-14 01:33:20.000000'),
    (1000000017034, @s1, 1000000003010, NULL, 'PUBLIC', '실습 코드 따라 치는 중입니다', 2600000, '2026-07-14 01:43:20.000000'),
    (1000000017035, @s1, 1000000003002, NULL, 'PUBLIC', '스토어를 파일로 분리해도 되나요?', 2750000, '2026-07-14 01:45:50.000000'),
    (1000000017036, @s1, 1000000003001, NULL, 'PUBLIC', '분리하는 편을 권합니다. 도메인 단위로 나누세요.', 2800000, '2026-07-14 01:46:40.000000'),
    (1000000017037, @s1, 1000000003016, NULL, 'PUBLIC', '훨씬 간결하네요', 2950000, '2026-07-14 01:49:10.000000'),
    (1000000017038, @s1, 1000000003022, 1000000003001, 'PRIVATE', '실습 코드가 에러가 나는데 어디를 볼까요?', 3000000, '2026-07-14 01:50:00.000000'),
    (1000000017039, @s1, 1000000003001, NULL, 'PUBLIC', '정리하고 질문 받겠습니다.', 3100000, '2026-07-14 01:51:40.000000'),
    (1000000017040, @s1, 1000000003013, NULL, 'PUBLIC', '오늘 수업 감사합니다!', 4380000, '2026-07-14 02:13:00.000000');


-- ============================================================================
-- 12. 상호작용 이벤트
-- ============================================================================
-- 손들기는 올림·내림이 짝을 이룬다(InteractionEventType 주석). 반응 8건과 강사 강제 음소거 1건을 더해 33건이다.
INSERT INTO `interaction_events` (`id`, `session_id`, `actor_participant_id`, `event_type`, `occurred_offset_ms`,
                                  `payload`, `created_at`)
VALUES
    (1000000018001, @s1, 1000000003009, 'HAND_RAISED', 545000, JSON_OBJECT(), '2026-07-14 01:09:05.000000'),
    (1000000018002, @s1, 1000000003009, 'HAND_LOWERED', 600000, JSON_OBJECT(), '2026-07-14 01:10:00.000000'),
    (1000000018003, @s1, 1000000003018, 'HAND_RAISED', 700000, JSON_OBJECT(), '2026-07-14 01:11:40.000000'),
    (1000000018004, @s1, 1000000003018, 'HAND_LOWERED', 770000, JSON_OBJECT(), '2026-07-14 01:12:50.000000'),
    (1000000018005, @s1, 1000000003021, 'HAND_RAISED', 860000, JSON_OBJECT(), '2026-07-14 01:14:20.000000'),
    (1000000018006, @s1, 1000000003021, 'HAND_LOWERED', 920000, JSON_OBJECT(), '2026-07-14 01:15:20.000000'),
    (1000000018007, @s1, 1000000003008, 'HAND_RAISED', 925000, JSON_OBJECT(), '2026-07-14 01:15:25.000000'),
    (1000000018008, @s1, 1000000003008, 'HAND_LOWERED', 990000, JSON_OBJECT(), '2026-07-14 01:16:30.000000'),
    (1000000018009, @s1, 1000000003002, 'HAND_RAISED', 1200000, JSON_OBJECT(), '2026-07-14 01:20:00.000000'),
    (1000000018010, @s1, 1000000003002, 'HAND_LOWERED', 1265000, JSON_OBJECT(), '2026-07-14 01:21:05.000000'),
    (1000000018011, @s1, 1000000003017, 'HAND_RAISED', 1460000, JSON_OBJECT(), '2026-07-14 01:24:20.000000'),
    (1000000018012, @s1, 1000000003017, 'HAND_LOWERED', 1530000, JSON_OBJECT(), '2026-07-14 01:25:30.000000'),
    (1000000018013, @s1, 1000000003023, 'HAND_RAISED', 1540000, JSON_OBJECT(), '2026-07-14 01:25:40.000000'),
    (1000000018014, @s1, 1000000003023, 'HAND_LOWERED', 1620000, JSON_OBJECT(), '2026-07-14 01:27:00.000000'),
    (1000000018015, @s1, 1000000003003, 'HAND_RAISED', 1810000, JSON_OBJECT(), '2026-07-14 01:30:10.000000'),
    (1000000018016, @s1, 1000000003003, 'HAND_LOWERED', 1880000, JSON_OBJECT(), '2026-07-14 01:31:20.000000'),
    (1000000018017, @s1, 1000000003020, 'HAND_RAISED', 1890000, JSON_OBJECT(), '2026-07-14 01:31:30.000000'),
    (1000000018018, @s1, 1000000003020, 'HAND_LOWERED', 1960000, JSON_OBJECT(), '2026-07-14 01:32:40.000000'),
    (1000000018019, @s1, 1000000003010, 'HAND_RAISED', 2620000, JSON_OBJECT(), '2026-07-14 01:43:40.000000'),
    (1000000018020, @s1, 1000000003010, 'HAND_LOWERED', 2700000, JSON_OBJECT(), '2026-07-14 01:45:00.000000'),
    (1000000018021, @s1, 1000000003002, 'HAND_RAISED', 2730000, JSON_OBJECT(), '2026-07-14 01:45:30.000000'),
    (1000000018022, @s1, 1000000003002, 'HAND_LOWERED', 2810000, JSON_OBJECT(), '2026-07-14 01:46:50.000000'),
    (1000000018023, @s1, 1000000003022, 'HAND_RAISED', 3010000, JSON_OBJECT(), '2026-07-14 01:50:10.000000'),
    (1000000018024, @s1, 1000000003022, 'HAND_LOWERED', 3090000, JSON_OBJECT(), '2026-07-14 01:51:30.000000'),
    (1000000018025, @s1, 1000000003004, 'REACTION', 60000, JSON_OBJECT('reaction', 'LIKE'), '2026-07-14 01:01:00.000000'),
    (1000000018026, @s1, 1000000003012, 'REACTION', 480000, JSON_OBJECT('reaction', 'WOW'), '2026-07-14 01:08:00.000000'),
    (1000000018027, @s1, 1000000003015, 'REACTION', 1015000, JSON_OBJECT('reaction', 'CLAP'), '2026-07-14 01:16:55.000000'),
    (1000000018028, @s1, 1000000003006, 'REACTION', 1530000, JSON_OBJECT('reaction', 'HEART'), '2026-07-14 01:25:30.000000'),
    (1000000018029, @s1, 1000000003011, 'REACTION', 2560000, JSON_OBJECT('reaction', 'CHEER'), '2026-07-14 01:42:40.000000'),
    (1000000018030, @s1, 1000000003016, 'REACTION', 2960000, JSON_OBJECT('reaction', 'CLAP'), '2026-07-14 01:49:20.000000'),
    (1000000018031, @s1, 1000000003019, 'REACTION', 3120000, JSON_OBJECT('reaction', 'CELEBRATE'), '2026-07-14 01:52:00.000000'),
    (1000000018032, @s1, 1000000003013, 'REACTION', 4400000, JSON_OBJECT('reaction', 'CLAP'), '2026-07-14 02:13:20.000000'),
    -- 행위자는 강사이고 대상은 payload 에 담긴다(MuteParticipantService).
    (1000000018033, @s1, 1000000003001, 'FORCE_MUTED', 2300000, JSON_OBJECT('targetParticipantId', '1000000003007'), '2026-07-14 01:38:20.000000');


-- ============================================================================
-- 13. 리포트
-- ============================================================================
INSERT INTO `session_reports` (`id`, `session_id`, `summary`, `published_at`, `created_at`, `updated_at`)
VALUES (1000000019001, @s1,
        '이번 수업은 지역 상태에서 출발해 props drilling, Context 리렌더링, 메모이제이션, 외부 상태관리 라이브러리 순으로 이어졌습니다. Context 구독과 리렌더링을 다룬 8~15분 구간에서 확인이 필요한 신호가 가장 많이 모였고, Zustand 실습 구간에서는 참여도가 수업 전체에서 가장 높았습니다.',
        '2026-07-14 03:05:00.000000', '2026-07-14 03:00:00.000000', '2026-07-14 03:05:00.000000');

INSERT INTO `instructor_reports` (`id`, `session_id`, `overall_feedback`, `published_at`, `created_at`, `updated_at`)
VALUES (1000000020001, @s1,
        '전체 흐름은 개념 → 문제 → 해법 순으로 잘 짜여 있었습니다. 다만 Context 리렌더링을 설명한 구간에서 예제보다 원리를 먼저 다뤄 이해 확인 신호가 몰렸습니다. 실습 구간의 참여도가 뚜렷하게 높았던 만큼, 어려운 개념도 예제를 먼저 보여준 뒤 원리로 넘어가는 순서를 시도해볼 만합니다.',
        '2026-07-14 03:05:00.000000', '2026-07-14 03:00:00.000000', '2026-07-14 03:05:00.000000');

-- 평가 분야 4종은 V1 주석이 정본이다.
INSERT INTO `instructor_report_scores` (`id`, `instructor_report_id`, `evaluation_type`, `score`, `created_at`, `updated_at`)
VALUES
    (1000000021001, 1000000020001, 'DELIVERY', 88, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000021002, 1000000020001, 'STRUCTURE_FLOW', 84, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000021003, 1000000020001, 'INTERACTION', 71, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000021004, 1000000020001, 'DIFFICULTY_CONTROL', 76, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000');

-- insight_type·tip_type 은 아직 정본 목록이 없다. 강사 리포트 조회 API 일감이 목록을 확정하면 여기도 함께 고친다.
INSERT INTO `instructor_report_insights` (`id`, `instructor_report_id`, `insight_type`, `content`,
                                          `started_offset_ms`, `ended_offset_ms`, `created_at`, `updated_at`)
VALUES
    (1000000022001, 1000000020001, 'ATTENTION_DROP', 'Context 리렌더링 구간에서 이해 확인 신호가 집중적으로 발생했습니다.', 520000, 921000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000022002, 1000000020001, 'PRACTICE_EFFECT', '실습 구간의 참여도가 수업 전체에서 가장 높았습니다.', 2520000, 3079000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000022003, 1000000020001, 'RECOVERY_TREND', '후반부로 갈수록 참여도가 회복되는 흐름입니다.', NULL, NULL, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000022004, 1000000020001, 'QUESTION_RESPONSE', '질문이 몰린 구간의 응답 시간이 짧았습니다.', 922000, 1439000, '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000');

INSERT INTO `instructor_report_tips` (`id`, `instructor_report_id`, `tip_type`, `title`, `content`, `created_at`, `updated_at`)
VALUES
    (1000000023001, 1000000020001, 'DIFFICULT_SECTION', '어려운 구간 보강', 'Context 리렌더링 구간에 예제와 실습 시간을 더 배치해 보세요.', '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000023002, 1000000020001, 'QUESTION_TIME', '질문 응답 시간 확보', '질문이 몰리는 구간 뒤에 답변 시간을 명시적으로 확보해 보세요.', '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000023003, 1000000020001, 'VISUAL_AID', '시각 자료 활용 강화', '리렌더 흐름을 다이어그램으로 먼저 보여주면 이해가 빨라집니다.', '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'),
    (1000000023004, 1000000020001, 'PARTICIPATION', '학생 참여 유도', '개념 설명 뒤 짧은 확인 질문을 넣어 참여를 끌어올려 보세요.', '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000');

-- 리포트 요약은 학생 전원에게 채운다. 참가자 ID 에서 리포트 ID 를 계산한다(1000000003002 → 1000000024002).
INSERT INTO `student_reports` (`id`, `session_id`, `session_participant_id`, `participation_summary`,
                               `published_at`, `created_at`, `updated_at`)
SELECT 1000000024000 + (p.`id` - 1000000003000), @s1, p.`id`,
       CASE (p.`id` - 1000000003001) % 3
           WHEN 0 THEN '수업 전반에 걸쳐 안정적으로 참여했습니다. Context 리렌더링 구간에서 잠깐 집중이 흔들렸지만 실습 구간에서 다시 끌어올렸습니다.'
           WHEN 1 THEN '도입과 실습 구간의 참여도가 특히 높았습니다. 개념 설명이 이어진 중반 구간은 다시 확인해 두면 좋겠습니다.'
           ELSE '질문과 반응으로 수업에 활발히 참여했습니다. 메모이제이션 구간에서 확인이 필요한 신호가 반복됐습니다.'
       END,
       '2026-07-14 03:05:00.000000', '2026-07-14 03:00:00.000000', '2026-07-14 03:05:00.000000'
FROM `session_participants` p
WHERE p.`session_id` = @s1 AND p.`role` = 'STUDENT';


-- ============================================================================
-- 14. 복습 추천과 퀴즈 — 시연 학생 계정에만 채운다
-- ============================================================================
-- 학생 전원에게 퀴즈를 주면 600행이 넘고 시연에 쓰이지 않는다. 실제 로그인 학생 세 사람의 리포트
-- (참가자 1000000003002~3004 → 리포트 1000000024002~24004)에만 붙인다.
--
-- 세 사람 몫을 세 번 적지 않고 slot 1~3 을 CROSS JOIN 해 만든다. 문제와 선택지 문구가 한 곳에만 있어야
-- 셋의 내용이 갈라지지 않는다. ID 는 slot 을 자릿수로 넣어 계산한다.

-- recommendation_type 4종은 V1 주석이 정본이다.
INSERT INTO `review_recommendations` (`id`, `student_report_id`, `recommendation_type`, `title`, `description`,
                                      `started_offset_ms`, `ended_offset_ms`, `priority`, `created_at`, `updated_at`)
SELECT 1000000025000 + s.`slot` * 10 + t.`ord`,
       1000000024001 + s.`slot`,
       t.`recommendation_type`, t.`title`, t.`description`, t.`started_offset_ms`, t.`ended_offset_ms`, t.`ord`,
       '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'
FROM (SELECT 1 AS `slot` UNION ALL SELECT 2 UNION ALL SELECT 3) s
CROSS JOIN (
    SELECT 1 AS `ord`, 'CONFUSED' AS `recommendation_type`, 'useMemo 메모이제이션 패턴' AS `title`,
           '헷갈림 응답과 같은 개념에서 반복된 확인 신호가 함께 근거가 됐습니다.' AS `description`,
           1440000 AS `started_offset_ms`, 1859000 AS `ended_offset_ms`
    UNION ALL SELECT 2, 'MISSED', 'Context API 리렌더링', '놓침 응답과 프롬프트 미응답이 같은 구간에 함께 있었습니다.', 520000, 921000
    UNION ALL SELECT 3, 'QUESTION', '상태관리 라이브러리 비교', '직접 남긴 비공개 질문이 이 개념 설명 구간을 가리킵니다.', 1860000, 2519000
    UNION ALL SELECT 4, 'REPEAT', 'props drilling 과 상태 위치', '같은 개념에서 확인 필요 신호가 반복됐습니다.', 0, 519000
    UNION ALL SELECT 5, 'REPEAT', 'Zustand 스토어 구조', '실습 구간에서 같은 지점을 여러 번 되짚었습니다.', 2520000, 3079000
) t;

INSERT INTO `quizzes` (`id`, `student_report_id`, `title`, `description`, `estimated_duration_minutes`,
                       `published_at`, `created_at`, `updated_at`)
SELECT 1000000026000 + s.`slot`, 1000000024001 + s.`slot`, 'React 상태관리 이해도 퀴즈',
       '오늘 수업에서 확인이 필요했던 개념을 중심으로 만들었습니다.', 5,
       '2026-07-14 03:05:00.000000', '2026-07-14 03:00:00.000000', '2026-07-14 03:05:00.000000'
FROM (SELECT 1 AS `slot` UNION ALL SELECT 2 UNION ALL SELECT 3) s;

INSERT INTO `quiz_questions` (`id`, `quiz_id`, `question_text`, `explanation`, `question_order`, `created_at`, `updated_at`)
SELECT 1000000027000 + s.`slot` * 10 + t.`qorder`, 1000000026000 + s.`slot`,
       t.`question_text`, t.`explanation`, t.`qorder`,
       '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'
FROM (SELECT 1 AS `slot` UNION ALL SELECT 2 UNION ALL SELECT 3) s
CROSS JOIN (
    SELECT 1 AS `qorder`,
           'Context Provider 의 value 가 바뀔 때 하위 컴포넌트가 리렌더되는 주된 이유는?' AS `question_text`,
           '객체 리터럴을 value 로 넘기면 매 렌더마다 새 참조가 만들어져, 이를 구독하는 하위 컴포넌트가 모두 리렌더됩니다.' AS `explanation`
    UNION ALL SELECT 2, 'Provider value 의 불필요한 리렌더를 줄이는 가장 적절한 방법은?',
           'value 를 useMemo 로 감싸 참조를 안정화하면 의존성이 실제로 바뀔 때만 새 참조가 생깁니다.'
    UNION ALL SELECT 3, 'props drilling 에 대한 설명으로 옳은 것은?',
           '실제로 사용하지 않는 중간 계층이 단지 아래로 props 를 전달만 하는 구조를 말합니다.'
    UNION ALL SELECT 4, '외부 상태관리 라이브러리 도입을 고려할 만한 상황은?',
           '전역 상태가 넓고 미들웨어나 복잡한 비동기 흐름이 필요할 때 라이브러리가 유리합니다.'
    UNION ALL SELECT 5, 'useCallback 이 실제로 필요한 경우는?',
           'React.memo 된 자식에게 함수를 props 로 넘길 때 참조 안정화를 위해 필요합니다.'
) t;

-- 다섯 문제 모두 정답이 2번이다. 아래 응답 블록이 그 사실을 쓴다.
INSERT INTO `quiz_options` (`id`, `quiz_question_id`, `option_text`, `is_correct`, `option_order`, `created_at`, `updated_at`)
SELECT 1000000028000 + s.`slot` * 100 + t.`qorder` * 10 + t.`oorder`,
       1000000027000 + s.`slot` * 10 + t.`qorder`,
       t.`option_text`, t.`oorder` = 2, t.`oorder`,
       '2026-07-14 03:00:00.000000', '2026-07-14 03:00:00.000000'
FROM (SELECT 1 AS `slot` UNION ALL SELECT 2 UNION ALL SELECT 3) s
CROSS JOIN (
    SELECT 1 AS `qorder`, 1 AS `oorder`, '상태가 전역이라서' AS `option_text`
    UNION ALL SELECT 1, 2, 'value 객체의 참조가 매 렌더마다 새로 생겨서'
    UNION ALL SELECT 1, 3, 'useEffect 가 실행되어서'
    UNION ALL SELECT 1, 4, 'key 가 바뀌어서'
    UNION ALL SELECT 2, 1, 'useState 로 감싼다'
    UNION ALL SELECT 2, 2, 'value 를 useMemo 로 메모이즈한다'
    UNION ALL SELECT 2, 3, '컴포넌트를 하나로 합친다'
    UNION ALL SELECT 2, 4, 'key 를 고정한다'
    UNION ALL SELECT 3, 1, '상태를 전역 저장소에 두는 것'
    UNION ALL SELECT 3, 2, '중간 컴포넌트들이 쓰지 않는 props 를 전달만 하는 상황'
    UNION ALL SELECT 3, 3, 'props 를 삭제하는 최적화'
    UNION ALL SELECT 3, 4, '상태를 지역화하는 패턴'
    UNION ALL SELECT 4, 1, '상태가 지역적일 때'
    UNION ALL SELECT 4, 2, '전역성이 크고 미들웨어·비동기 흐름이 필요할 때'
    UNION ALL SELECT 4, 3, '컴포넌트가 하나뿐일 때'
    UNION ALL SELECT 4, 4, '스타일링이 복잡할 때'
    UNION ALL SELECT 5, 1, '모든 함수에 항상'
    UNION ALL SELECT 5, 2, '메모이즈된 자식에 함수를 props 로 넘길 때'
    UNION ALL SELECT 5, 3, '상태를 만들 때'
    UNION ALL SELECT 5, 4, '렌더링을 완전히 막을 때'
) t;

-- 문제당 응답은 하나뿐이다(UK_QUIZ_ANSWERS_QUIZ_QUESTION). 정답은 2번이고, (slot + 문제 번호)가 4의
-- 배수인 자리만 3번을 골라 오답으로 둔다 — 세 사람의 채점 결과가 서로 다르게 보이게 하려는 것이다.
INSERT INTO `quiz_answers` (`id`, `quiz_question_id`, `selected_quiz_option_id`, `answered_at`, `created_at`, `updated_at`)
SELECT 1000000029000 + s.`slot` * 10 + t.`qorder`,
       1000000027000 + s.`slot` * 10 + t.`qorder`,
       1000000028000 + s.`slot` * 100 + t.`qorder` * 10 + IF((s.`slot` + t.`qorder`) % 4 = 0, 3, 2),
       DATE_ADD('2026-07-14 04:10:00.000000', INTERVAL (s.`slot` - 1) * 600 + t.`qorder` * 60 SECOND),
       '2026-07-14 04:30:00.000000', '2026-07-14 04:30:00.000000'
FROM (SELECT 1 AS `slot` UNION ALL SELECT 2 UNION ALL SELECT 3) s
CROSS JOIN (
    SELECT 1 AS `qorder` UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
) t;
