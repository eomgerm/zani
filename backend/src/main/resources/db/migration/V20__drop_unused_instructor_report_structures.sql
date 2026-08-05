-- 아무도 읽지 않는 강사 리포트 구조를 지운다. (S15P11A105-250)
--
-- instructor_report_tips: 화면이 "수업 개선 TIP" 카드와 "인사이트" 카드를 "수업 인사이트" 하나로
-- 합쳤다. 합쳐진 항목은 제목 + 근거 + 제안 + 구간이고, 구간 시각은 instructor_report_insights
-- 에만 있어 그쪽으로 접는 편이 붙일 컬럼이 적다. V1 이후 이 테이블을 읽거나 쓰는 코드가 없고,
-- 유일한 미래 소비자였던 강사 리포트 조회(S15P11A105-109)도 인사이트만 읽는다.
--
-- insight_type: 유형을 두지 않고 AI 가 제목을 직접 짓는다. 화면 아이콘도 하나로 통일되어 유형으로
-- 갈라야 할 표시가 없다. NOT NULL 이라 상수를 박아 넣는 선택지가 있었지만 아무도 읽지 않는 죽은
-- 데이터가 된다.
--
-- 파괴적 DDL 이라 확장(V14)과 파일을 나눴다. 복구는 새 마이그레이션으로만 가능하다 — 되돌리는
-- 대신 앞으로 가는 변경을 쓴다.

DROP TABLE `instructor_report_tips`;

ALTER TABLE `instructor_report_insights` DROP COLUMN `insight_type`;
