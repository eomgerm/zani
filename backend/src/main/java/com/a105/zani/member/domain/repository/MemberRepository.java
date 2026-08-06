package com.a105.zani.member.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.a105.zani.member.domain.model.Member;

public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findById(Long id);

    /**
     * 탈퇴하지 않은 회원만 찾는다. 본인 확인이 필요한 곳(내 정보 조회·설정 변경)은 이쪽을 쓴다 — 탈퇴 직후에도 Access Token 은 최대 1시간 살아 있어서, 그냥 findById 로 찾으면
     * 그동안 자기 정보를 계속 읽고 고칠 수 있다. 이름 표시처럼 과거 기록을 보여 주는 곳은 탈퇴 회원도 찾아야 하므로 findById 를 그대로 쓴다.
     */
    Optional<Member> findActiveById(Long id);

    /**
     * 탈퇴 처리한다. deleted_at 을 찍고 google_subject 를 tombstone 으로 바꿔 같은 구글 계정이 새 회원으로 가입할 수 있게 비워 준다. email·display_name 은
     * 남긴다 — 과거 리포트·참여자 목록의 이름이 깨지면 안 되고, 최종 파기는 retention_expires_at 을 쓰는 별도 정리 작업의 몫이다.
     *
     * @return 이번 호출로 탈퇴 처리했으면 true. 없는 회원이거나 이미 탈퇴했으면 false.
     */
    boolean withdraw(Long id, Instant deletedAt);

    Optional<Member> findByGoogleSubject(String googleSubject);

    /** 여러 회원을 한 번에 가져온다. 없는 ID 는 결과에서 빠지므로 개수가 요청과 다를 수 있다. */
    List<Member> findAllByIds(Collection<Long> ids);
}
