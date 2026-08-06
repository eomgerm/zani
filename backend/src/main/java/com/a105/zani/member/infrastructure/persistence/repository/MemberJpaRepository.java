package com.a105.zani.member.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;

public interface MemberJpaRepository extends JpaRepository<MemberJpaEntity, Long> {

    Optional<MemberJpaEntity> findByGoogleSubject(String googleSubject);

    Optional<MemberJpaEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 탈퇴 처리. 엔티티를 읽어 저장하지 않고 조건부 UPDATE 한 번으로 끝낸다 — {@code deleted_at IS NULL} 을 조건에 두면 동시에 두 번 눌러도 한 번만 반영되고, 갱신 건수로
     * 이미 탈퇴한 회원인지 바로 알 수 있다.
     *
     * <p>벌크 UPDATE 는 엔티티 리스너를 타지 않아 {@code @LastModifiedDate} 가 동작하지 않으므로 updated_at 도 직접 넣는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MemberJpaEntity m
               SET m.deletedAt = :deletedAt,
                   m.updatedAt = :deletedAt,
                   m.googleSubject = :withdrawnGoogleSubject
             WHERE m.id = :id
               AND m.deletedAt IS NULL
            """)
    int withdraw(
            @Param("id") Long id,
            @Param("deletedAt") Instant deletedAt,
            @Param("withdrawnGoogleSubject") String withdrawnGoogleSubject);
}
