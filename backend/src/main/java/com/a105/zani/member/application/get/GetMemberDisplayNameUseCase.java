package com.a105.zani.member.application.get;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 회원의 표시 이름을 다른 도메인에 공개하는 member 도메인의 읽기 UseCase. member 리포지토리는 member 도메인 안에만 머물고, 다른 도메인은 이 UseCase만 사용한다. */
public interface GetMemberDisplayNameUseCase {

    /** 회원의 표시 이름. 회원이 없으면 비어 있다. */
    Optional<String> getDisplayName(GetMemberDisplayNameQuery query);

    /**
     * 여러 회원의 표시 이름을 회원 ID 로 찾을 수 있게 돌려준다.
     *
     * <p>목록 화면이 회원마다 한 번씩 물으면 목록 길이만큼 쿼리가 나간다. 그렇다고 부르는 쪽이 member 리포지토리를 직접 열면 이 UseCase 를 둔 이유가 사라지므로, 묶어서 묻는 길을 여기에
     * 둔다.
     *
     * <p>없는 회원은 결과에서 빠진다. 부르는 쪽이 기본값을 정해야 한다.
     *
     * <p>기본 구현은 한 명씩 묻는다 — 결과는 정확하지만 묶어서 묻는 이점이 없다. <b>저장소를 가진 구현은 반드시 재정의한다.</b> 여기에 기본 구현을 두는 것은 이 인터페이스를 람다로 대역화하는
     * 곳(세션 유스케이스 테스트들)이 메서드가 늘 때마다 깨지지 않게 하기 위해서다.
     */
    default Map<Long, String> getDisplayNames(GetMemberDisplayNamesQuery query) {
        Map<Long, String> names = new HashMap<>();
        for (Long memberId : query.memberIds()) {
            getDisplayName(new GetMemberDisplayNameQuery(memberId)).ifPresent(name -> names.put(memberId, name));
        }
        return names;
    }
}
