package com.a105.zani.member.application;

import java.util.Optional;

/** member 도메인이 공개하는 표시 이름 조회 API. 다른 도메인은 members 테이블을 직접 읽지 않고 이 인터페이스로만 표시 이름을 얻는다(스키마·정책을 member 안에 캡슐화). */
public interface MemberDisplayNameReader {

    Optional<String> findDisplayName(Long memberId);
}
