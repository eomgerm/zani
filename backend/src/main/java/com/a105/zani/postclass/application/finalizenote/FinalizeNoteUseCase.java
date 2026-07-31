package com.a105.zani.postclass.application.finalizenote;

import com.a105.zani.postclass.application.exception.PipelineJobUnavailableException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;

public interface FinalizeNoteUseCase {

    /**
     * 강사 메모를 확정한다(NOTE-003). 확정 후에는 수정·재생성할 수 없다.
     *
     * <p>중복 확정은 오류가 아니라 멱등 성공이다 — 강사가 버튼을 두 번 누르거나 자동 확정과 겹쳤을 때 실패로 돌려주면, 이미 확정된 수업을 두고 클라이언트가 재시도를 반복한다. 초안이 없는 세션에
     * 확정이 동시에 두 번 와도 마찬가지다.
     *
     * <p>사후 처리 작업 등록은 확정과 같은 트랜잭션에서 일어난다(NOTE-004). 그 등록이 실패하면 확정까지 되돌린다 — 확정만 남고 작업이 없으면 그 수업의 분석은 영원히 시작되지 않고, 확정은 다시
     * 할 수 없어 복구 경로가 없다.
     *
     * @throws NotSessionMemberException 해당 세션의 멤버가 아님
     * @throws SessionNotFoundException 세션 없음
     * @throws SessionNotEndedException 아직 진행 중인 세션
     * @throws NotSessionInstructorException 세션 강사가 아님
     * @throws PipelineJobUnavailableException 사후 처리 작업 저장소에 쓸 수 없어 확정을 되돌림
     */
    FinalizeNoteResult finalizeNote(FinalizeNoteCommand command);
}
