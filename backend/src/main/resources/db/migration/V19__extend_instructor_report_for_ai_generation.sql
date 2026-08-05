-- 강사 리포트를 LLM 이 채울 수 있게 컬럼을 넓힌다. (S15P11A105-250)
--
-- question_count 는 서버가 chat_messages 행을 세지 않고 모델이 판단한 값을 굳혀 저장한다.
-- 공개 채팅에는 질문만 있지 않다 — "네", "감사합니다", "잘 들려요" 도 같은 테이블에 같은 모양으로
-- 들어온다. 행 수를 세면 그것까지 질문으로 세고, 반대로 물음표만 찾으면 "이 부분 다시 설명해주실 수
-- 있나요" 같은 완곡한 요청과 물음표 없는 질문을 놓친다. 무엇이 질문인지는 문장을 읽어야 알 수 있다.
-- 저장하는 이유는 판정 근거가 남지 않기 때문이다 — 같은 채팅을 나중에 다시 세면 모델이 다르게
-- 판단할 수 있고, 그러면 강사가 어제 본 숫자와 오늘 본 숫자가 달라진다.
--
-- title·suggestion 은 인사이트 카드의 제목과 제안이다. 화면이 "수업 개선 TIP" 카드와 "인사이트"
-- 카드를 "수업 인사이트" 하나로 합쳐, 한 인사이트가 제목·근거(content)·제안·구간을 함께 갖는다.
--
-- 셋 다 NULL 허용이다. 모델 응답에 의존하는 값이라 이후 스키마 변경 없이도 비어 있는 행이 생길 수
-- 있어야 한다. 필수 여부는 도메인 애그리거트(InstructorReport·ClassInsight)가 검증한다.

ALTER TABLE `instructor_reports`
    ADD COLUMN `question_count` INT NULL
        COMMENT '수업 전체에서 학생들이 남긴 질문 수. AI 가 공개 채팅에서 질문인 발화만 세어 판단한다'
        AFTER `overall_feedback`;

ALTER TABLE `instructor_report_insights`
    ADD COLUMN `title` VARCHAR(200) NULL
        COMMENT '인사이트 제목. AI 가 유형 목록 없이 직접 짓는다'
        AFTER `instructor_report_id`,
    ADD COLUMN `suggestion` TEXT NULL
        COMMENT 'AI 가 제시한 개선 제안. content 는 그 제안의 근거다'
        AFTER `content`;
