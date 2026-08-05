-- 복습 추천의 근거 유형을 학생 화면이 실제로 구분해 보여주는 다섯 가지로 다시 정의한다.
--
-- 이전 네 가지(CONFUSED, MISSED, QUESTION, REPEAT)에는 두 문제가 있었다.
--   - 프롬프트 미응답이 들어갈 자리가 없었다. 학생 상태 6종에 NON_RESPONSE 가 있고
--     리포트도 그것을 근거로 쓰는데, 추천 유형에서는 헷갈림·놓침에 섞여 버렸다.
--   - REPEAT("반복 확인")는 관측이 아니라 관측의 해석이다. 프롬프트가 반복 발동한 원인은
--     저참여 3연속이므로(attention-coaching-context §2), 학생에게 보여줄 근거는
--     "집중이 떨어진 구간" 이다. 해석을 유형 이름에 넣으면 학생이 무엇을 근거로 추천받았는지
--     되짚을 수 없다.
--
-- 다섯 가지는 관측 하나에 하나씩 대응한다.
--   CONFUSED       프롬프트에 "헷갈려요" 로 응답한 구간
--   MISSED         프롬프트에 "놓쳤어요" 로 응답한 구간
--   NO_RESPONSE    프롬프트에 응답하지 않은 구간
--   LOW_ENGAGEMENT 참여도 판정이 낮게 이어진 구간
--   QUESTION       학생이 질문을 남긴 구간
--
-- VARCHAR 컬럼이라 값 자체는 제약이 아니지만 주석이 계약을 서술하고 있어 함께 고친다.
-- V9 가 recording 컬럼 주석을 같은 이유로 정정했다.

ALTER TABLE `review_recommendations`
    MODIFY COLUMN `recommendation_type` VARCHAR(30) NOT NULL
    COMMENT 'CONFUSED, MISSED, NO_RESPONSE, LOW_ENGAGEMENT 또는 QUESTION';

-- 좁아진 유형에 남아 있는 행을 옮긴다. 249 가 아직 어디서도 실행되지 않았으므로 대상은
-- 시연 시드가 넣은 행뿐이고(시드도 같은 변경에서 고쳤다), 손으로 넣은 dev 데이터가 있다면
-- 여기서 함께 정리된다. 범위가 좁아 한 문장으로 끝난다.
UPDATE `review_recommendations`
   SET `recommendation_type` = 'LOW_ENGAGEMENT'
 WHERE `recommendation_type` = 'REPEAT';
