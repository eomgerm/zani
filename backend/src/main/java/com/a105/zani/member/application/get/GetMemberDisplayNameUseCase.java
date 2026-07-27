package com.a105.zani.member.application.get;

import java.util.Optional;

/** 회원의 표시 이름을 다른 도메인에 공개하는 member 도메인의 읽기 UseCase. member 리포지토리는 member 도메인 안에만 머물고, 다른 도메인은 이 UseCase만 사용한다. */
public interface GetMemberDisplayNameUseCase {

    /** 회원의 표시 이름. 회원이 없으면 비어 있다. */
    Optional<String> getDisplayName(GetMemberDisplayNameQuery query);
}
