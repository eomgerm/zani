package com.a105.zani.member.domain.model;

/** member 도메인 모델. 프레임워크·JPA에 의존하지 않는 순수 클래스다. */
public class Member {

    private final Long id;
    private final String displayName;

    private Member(Long id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static Member reconstitute(Long id, String displayName) {
        return new Member(id, displayName);
    }

    public Long id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }
}
