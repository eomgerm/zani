-- 프롬프트 응답 값을 확정 계약 네 가지(OK, CONFUSED, MISSED, NON_RESPONSE)에 맞춘다.
-- 근거: .agents/attention-coaching-context.md §5(프롬프트 3종·전송 값), §5.2(응답은 집계를 바꾸지 않는다)
--
-- V4 를 고치지 않고 새 버전을 내는 이유: V4 는 이미 dev 에 머지돼 공유 환경에 적용됐다.
-- 적용된 마이그레이션의 내용을 바꾸면 체크섬이 어긋나고, validate-on-migrate 가 켜져 있어
-- 그 환경의 애플리케이션이 기동하지 못한다.

-- 티켓 82 시절에 쌓인 행에는 폐기된 이름이 남아 있다. 읽을 때 PromptAnswer.valueOf 가 터진다.
UPDATE `check_prompts` SET `response` = 'OK'
    WHERE `trigger_type` = 'UNDERSTANDING_CHECK' AND `response` = 'UNDERSTOOD';

UPDATE `check_prompts` SET `response` = 'NON_RESPONSE'
    WHERE `trigger_type` = 'UNDERSTANDING_CHECK' AND `response` = 'NO_RESPONSE';

-- 자세 안내·카메라 안내 행은 건드리지 않고 그대로 남긴다.
-- 브라우저가 그 두 프롬프트의 응답을 더는 보내지 않으므로(티켓 81) 새로 쌓이지 않고,
-- 조회는 trigger_type = 'UNDERSTANDING_CHECK' 로만 이뤄져 읽히지도 않는다.
-- 지우면 check_prompt_evidences 의 참조까지 함께 정리해야 하는데, 그건 수업 기록을 없애는 일이다.

ALTER TABLE `check_prompts`
    MODIFY COLUMN `trigger_type` VARCHAR(50) NOT NULL COMMENT '프롬프트 종류. 서버가 기록하는 것은 UNDERSTANDING_CHECK 뿐이다(자세·카메라 안내는 브라우저 안에서 끝난다)',
    MODIFY COLUMN `response` VARCHAR(30) NULL COMMENT 'OK, CONFUSED, MISSED 또는 NON_RESPONSE';
