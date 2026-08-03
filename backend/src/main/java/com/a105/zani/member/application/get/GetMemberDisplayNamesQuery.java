package com.a105.zani.member.application.get;

import java.util.Collection;

/**
 * 여러 회원의 표시 이름을 한 번에 묻는다.
 *
 * <p>목록 화면처럼 회원이 여럿인 자리에서 한 명씩 물으면 목록 길이만큼 쿼리가 나간다(N+1). 그렇다고 부르는 쪽이 member 리포지토리를 직접 열면 도메인 경계가 무너지므로, 묶어서 묻는 길을
 * member 안에 둔다.
 */
public record GetMemberDisplayNamesQuery(Collection<Long> memberIds) {}
