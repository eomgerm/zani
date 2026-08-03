-- 시연용 목업 데이터(db/seed/R__demo_seed.sql)가 제대로 들어갔는지 확인한다.
--
-- 실행: mysql -u root -p zani < backend/scripts/verify-demo-seed.sql
--
-- 두 가지를 본다.
--   1) 테이블별 시드 대역 행 수 — 기대치와 다르면 그 블록이 실패했거나 부분 적용됐다.
--   2) 변수 블록 이메일의 members 매칭 결과 — 매칭에 실패하면 그 사람 화면에는 시연 세션이 뜨지 않는다.
--
-- 아래 다섯 이메일은 R__demo_seed.sql 의 변수 블록과 같은 값으로 유지한다.
SET @instructor_1_email = 'skyrider0618@gmail.com';
SET @instructor_2_email = 'sangeun4153@gmail.com';
SET @student_1_email = 'oganesson12@gmail.com';
SET @student_2_email = 'yjhn0410@gmail.com';
SET @student_3_email = 'fishbread00@gmail.com';

SELECT '=== 1. 시드 대역 행 수 ===' AS `check`;

SELECT `table_name`, `row_count`, `expected`, IF(`row_count` = `expected`, 'OK', 'MISMATCH') AS `result`
FROM (
    SELECT 'members(가짜 학생)' AS `table_name`, COUNT(*) AS `row_count`, 20 AS `expected` FROM `members` WHERE `id` BETWEEN 1000000001005 AND 1000000001024
    UNION ALL SELECT 'sessions', COUNT(*), 3 FROM `sessions` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'session_participants', COUNT(*), 34 FROM `session_participants` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'session_status_changes', COUNT(*), 6 FROM `session_status_changes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'session_sections', COUNT(*), 7 FROM `session_sections` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'transcripts', COUNT(*), 1 FROM `transcripts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'instructor_notes', COUNT(*), 2 FROM `instructor_notes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'pipeline_jobs', COUNT(*), 2 FROM `pipeline_jobs` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'recordings', COUNT(*), 26 FROM `recordings` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'recording_files', COUNT(*), 26 FROM `recording_files` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'attention_events', COUNT(*), 10212 FROM `attention_events` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'group_alerts', COUNT(*), 7 FROM `group_alerts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'group_alert_response_counts', COUNT(*), 28 FROM `group_alert_response_counts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'coaching_histories', COUNT(*), 7 FROM `coaching_histories` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'coaching_history_response_counts', COUNT(*), 35 FROM `coaching_history_response_counts` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'chat_messages', COUNT(*), 40 FROM `chat_messages` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'interaction_events', COUNT(*), 33 FROM `interaction_events` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'session_reports', COUNT(*), 1 FROM `session_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'instructor_reports', COUNT(*), 1 FROM `instructor_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'instructor_report_scores', COUNT(*), 4 FROM `instructor_report_scores` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'instructor_report_insights', COUNT(*), 4 FROM `instructor_report_insights` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'instructor_report_tips', COUNT(*), 4 FROM `instructor_report_tips` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'student_reports', COUNT(*), 23 FROM `student_reports` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'review_recommendations', COUNT(*), 15 FROM `review_recommendations` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'quizzes', COUNT(*), 3 FROM `quizzes` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'quiz_questions', COUNT(*), 15 FROM `quiz_questions` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'quiz_options', COUNT(*), 60 FROM `quiz_options` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
    UNION ALL SELECT 'quiz_answers', COUNT(*), 15 FROM `quiz_answers` WHERE `id` BETWEEN 1000000000000 AND 1000000999999
) `counts`;

-- 프롬프트 건수는 저참여 이벤트 분포에서 뽑히므로 정확히 고정되지 않는다. 30~41건이면 정상이다.
SELECT '=== 2. 이해 확인 프롬프트(30~41건이면 정상) ===' AS `check`;

SELECT COUNT(*) AS `check_prompts`,
       (SELECT COUNT(*) FROM `check_prompt_evidences` WHERE `id` BETWEEN 1000000000000 AND 1000000999999) AS `evidences`,
       IF(COUNT(*) BETWEEN 30 AND 41, 'OK', 'CHECK') AS `result`
FROM `check_prompts`
WHERE `id` BETWEEN 1000000000000 AND 1000000999999;

SELECT '=== 3. 변수 블록 이메일 매칭 ===' AS `check`;

SELECT `slot`, `email`,
       (SELECT `id` FROM `members` m WHERE m.`email` = `slots`.`email` AND m.`deleted_at` IS NULL ORDER BY m.`id` LIMIT 1) AS `member_id`,
       IF(EXISTS (SELECT 1 FROM `members` m WHERE m.`email` = `slots`.`email` AND m.`deleted_at` IS NULL),
          'MATCHED',
          'NOT MATCHED — 로그인한 적이 없어 members 행이 없다. 가짜 회원이 대신 들어갔고 이 계정 화면에는 시연 세션이 뜨지 않는다') AS `result`
FROM (
    SELECT 'instructor_1' AS `slot`, @instructor_1_email AS `email`
    UNION ALL SELECT 'instructor_2', @instructor_2_email
    UNION ALL SELECT 'student_1', @student_1_email
    UNION ALL SELECT 'student_2', @student_2_email
    UNION ALL SELECT 'student_3', @student_3_email
) `slots`;

SELECT '=== 4. 시연 세션 참가자로 실제로 연결됐는지 ===' AS `check`;

SELECT s.`title`, s.`status`, s.`analysis_status`, p.`role`, m.`email`, m.`display_name`
FROM `sessions` s
JOIN `session_participants` p ON p.`session_id` = s.`id`
JOIN `members` m ON m.`id` = p.`member_id`
WHERE s.`id` BETWEEN 1000000000000 AND 1000000999999
  AND m.`email` IN (@instructor_1_email, @instructor_2_email, @student_1_email, @student_2_email, @student_3_email)
ORDER BY s.`id`, p.`role`, m.`email`;

-- sessions.host_member_id 와 role = 'INSTRUCTOR' 참가자는 같은 사람이어야 한다. 시드에서 강사를
-- 바꿀 때 한쪽만 고치기 쉬운데, 어긋나면 메모 작성자가 호스트가 아닌 상태가 되고 화면마다 다른
-- 사람이 강사로 보인다. FK 로는 잡히지 않아 여기서 본다.
SELECT '=== 5. 호스트와 강사 참가자 일치 ===' AS `check`;

SELECT s.`title`,
       s.`host_member_id`,
       p.`member_id` AS `instructor_participant_member_id`,
       IF(p.`member_id` <=> s.`host_member_id`, 'OK', 'MISMATCH') AS `result`
FROM `sessions` s
LEFT JOIN `session_participants` p
    ON p.`session_id` = s.`id` AND p.`role` = 'INSTRUCTOR'
WHERE s.`id` BETWEEN 1000000000000 AND 1000000999999
ORDER BY s.`id`;

-- 스케줄러가 시드 데이터를 건드렸는지 본다. 둘 다 시드를 적용한 직후에는 0 이어야 하고, 앱을 몇 분
-- 띄운 뒤에도 0 이어야 한다. 0 이 아니면 시드가 백그라운드 작업과 싸우고 있다는 뜻이다.
--   - DRAFT 메모: 비활성 스윕이 30분 지난 초안을 세션 구분 없이 확정한다. 시드는 초안을 남기지 않는다.
--   - 시드 세션의 대역 밖 pipeline_jobs: 스윕이 확정하면서 만든 TSID 행이다. 대역 삭제로는 지워지지 않는다.
SELECT '=== 6. 스케줄러가 만든 오염 ===' AS `check`;

SELECT 'DRAFT 상태 시드 메모' AS `item`, COUNT(*) AS `rows_found`,
       IF(COUNT(*) = 0, 'OK', 'CONTAMINATED — 비활성 스윕이 확정해 버린다') AS `result`
FROM `instructor_notes`
WHERE `id` BETWEEN 1000000000000 AND 1000000999999 AND `status` = 'DRAFT'
UNION ALL
SELECT '시드 세션의 대역 밖 pipeline_jobs', COUNT(*),
       IF(COUNT(*) = 0, 'OK', 'CONTAMINATED — 앱이 만든 행이 시연 세션에 붙어 있다')
FROM `pipeline_jobs`
WHERE `session_id` BETWEEN 1000000002001 AND 1000000002099
  AND `id` NOT BETWEEN 1000000000000 AND 1000000999999;

SELECT '=== 7. 참여도 이벤트 분포(구간·출력별) ===' AS `check`;

SELECT `detector_outcome`,
       COUNT(*) AS `events`,
       SUM(`attention_score` IS NULL) AS `score_is_null`,
       MIN(`occurred_offset_ms`) AS `first_offset_ms`,
       MAX(`occurred_offset_ms`) AS `last_offset_ms`
FROM `attention_events`
WHERE `id` BETWEEN 1000000000000 AND 1000000999999
GROUP BY `detector_outcome`
ORDER BY `events` DESC;
