package com.a105.zani.session.application.port;

import java.util.Optional;

/** 표시 이름 조회 포트. 표시 이름은 백엔드가 결정한다(프론트 입력 무시). */
public interface MemberDisplayNamePort {

    Optional<String> findDisplayName(Long memberId);
}
