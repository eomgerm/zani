-- 회원별 강의 리포트 완료 이메일 수신 on/off 설정을 members 에 저장한다.
-- 계정 설정 화면의 "강의 리포트 알림" 토글이 이 값을 바꾸고, 리포트 완료 알림 발송(REPORT_READY)이
-- 이 값을 게이트로 써서 OFF 인 회원을 수신자에서 제외한다.
--
-- 기존 회원을 포함해 기본값은 TRUE(수신)다. NOT NULL DEFAULT TRUE 라 이미 쌓인 행도 즉시 채워진다.
ALTER TABLE `members`
    ADD COLUMN `report_email_enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '강의 리포트 완료 이메일 수신 여부. 기본 TRUE' AFTER `profile_image_url`;
