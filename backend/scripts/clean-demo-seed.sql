-- 시연용 목업 데이터를 수동으로 지운다.
--
-- 실행: mysql -u root -p zani < backend/scripts/clean-demo-seed.sql
--
-- 언제 필요한가: 시드 세션에 실제 활동이 붙으면(시연 중 메모를 쓰거나 채팅을 남기면) 그 행은 TSID 라서
-- 시드 대역 밖에 있다. 그러면 R__demo_seed.sql 선두의 DELETE 가 FK 에 걸려 실패하고, 시드 실패는 곧
-- 애플리케이션 기동 실패다. 그때 이 스크립트로 시연 세션에 달린 행을 대역과 무관하게 정리한 뒤 다시 띄운다.
--
-- 지우는 범위는 시드 세션 4건(1000000002001~2004)에 달린 것 전부와 시드 대역 회원이다. 다른 세션은
-- 건드리지 않는다. FK 역순으로 지운다.

SET @s1 = 1000000002001;
SET @s2 = 1000000002002;
SET @s3 = 1000000002003;
SET @s4 = 1000000002004;

DELETE qa FROM `quiz_answers` qa
    JOIN `quiz_questions` qq ON qq.`id` = qa.`quiz_question_id`
    JOIN `quizzes` q ON q.`id` = qq.`quiz_id`
    JOIN `student_reports` sr ON sr.`id` = q.`student_report_id`
WHERE sr.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE qo FROM `quiz_options` qo
    JOIN `quiz_questions` qq ON qq.`id` = qo.`quiz_question_id`
    JOIN `quizzes` q ON q.`id` = qq.`quiz_id`
    JOIN `student_reports` sr ON sr.`id` = q.`student_report_id`
WHERE sr.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE qq FROM `quiz_questions` qq
    JOIN `quizzes` q ON q.`id` = qq.`quiz_id`
    JOIN `student_reports` sr ON sr.`id` = q.`student_report_id`
WHERE sr.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE q FROM `quizzes` q
    JOIN `student_reports` sr ON sr.`id` = q.`student_report_id`
WHERE sr.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE rr FROM `review_recommendations` rr
    JOIN `student_reports` sr ON sr.`id` = rr.`student_report_id`
WHERE sr.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `student_reports` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

DELETE t FROM `instructor_report_tips` t
    JOIN `instructor_reports` ir ON ir.`id` = t.`instructor_report_id`
WHERE ir.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE i FROM `instructor_report_insights` i
    JOIN `instructor_reports` ir ON ir.`id` = i.`instructor_report_id`
WHERE ir.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE sc FROM `instructor_report_scores` sc
    JOIN `instructor_reports` ir ON ir.`id` = sc.`instructor_report_id`
WHERE ir.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `instructor_reports` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `session_reports` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

DELETE e FROM `check_prompt_evidences` e
    JOIN `check_prompts` cp ON cp.`id` = e.`check_prompt_id`
WHERE cp.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `check_prompts` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

DELETE c FROM `coaching_history_response_counts` c
    JOIN `coaching_histories` ch ON ch.`id` = c.`coaching_history_id`
WHERE ch.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `coaching_histories` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

DELETE c FROM `group_alert_response_counts` c
    JOIN `group_alerts` ga ON ga.`id` = c.`group_alert_id`
WHERE ga.`session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `group_alerts` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `attention_events` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `interaction_events` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `chat_messages` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `recording_files` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `recordings` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `transcripts` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `session_sections` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `instructor_notes` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `pipeline_jobs` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `session_status_changes` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

-- 알림 outbox 는 sessions FK 가 없어 위 삭제로 정리되지 않는다. 리포트가 공개된 시드 세션에 대해
-- 발송 릴레이가 등록해 둔 행이 남을 수 있다.
DELETE FROM `notification_outbox` WHERE `session_id` IN (@s1, @s2, @s3, @s4);

DELETE FROM `session_participants` WHERE `session_id` IN (@s1, @s2, @s3, @s4);
DELETE FROM `sessions` WHERE `id` IN (@s1, @s2, @s3, @s4);

-- 시드 대역 가짜 회원. 실제 계정(대역 밖)은 지우지 않는다.
DELETE FROM `members` WHERE `id` BETWEEN 1000000000000 AND 1000000999999;
