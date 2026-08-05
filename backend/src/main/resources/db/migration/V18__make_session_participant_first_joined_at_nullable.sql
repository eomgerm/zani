-- 사후 자료 접근 자격을 실제 미디어 연결로 판정한다(FRD ACCESS-002, S15P11A105-267).
--
-- V1 은 `first_joined_at` 을 NOT NULL 로 두었고, `POST /sessions/join` 이 그 자리를 API 호출 시각으로 즉시
-- 채웠다. 그래서 초대 코드를 아는 사람이 입장 API 한 번만 부르면 미디어에 붙지 않아도 자격이 생겼다.
--
-- 자격은 이제 LiveKit `participant_joined` 통지에서만 채운다. 아직 붙지 않은 참가자를 표현할 자리가 필요해
-- NULL 을 허용한다. NULL 은 "참가 관계는 있으나 연결이 확인되지 않음" 이고, 곧 접근 자격 없음이다.
--
-- 기존 행의 값은 그대로 둔다. 이미 들어 있는 시각이 실제 연결이었는지 소급 판정할 근거가 없고, 잘못 박탈하는
-- 쪽이 잘못 부여하는 쪽보다 사용자에게 해롭다. 신규 행부터 새 규칙이 적용된다.
ALTER TABLE `session_participants`
    MODIFY COLUMN `first_joined_at` DATETIME(6) NULL COMMENT '실제 미디어 연결이 확인된 최초 시각. NULL 이면 사후 자료 접근 자격이 없다. 입장 API 호출로는 채우지 않는다';
