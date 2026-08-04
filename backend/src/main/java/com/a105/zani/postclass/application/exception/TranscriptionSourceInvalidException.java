package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 원본 트랙 경로를 원본 루트 아래로 풀 수 없다(S15P11A105-247).
 *
 * <p><b>재시도하지 않는다.</b> 파일이 없는 것과 다르다 — 마운트가 아직 올라오지 않았다면 다음 시도에 성공할 수 있으므로 그것은 분할 실패({@link AudioChunkFailedException},
 * 재시도 가능)로 다룬다. 반면 저장된 {@code storage_key} 가 비어 있거나 {@code ..} 로 루트를 벗어나는 것은 몇 번을 다시 풀어도 같다.
 *
 * <p>이 경우를 건너뛰고 나머지 트랙만 전사하면 안 된다. 그 트랙의 화자는 문서에서 통째로 빠지는데 문서에는 "완전함"({@code partial=false})으로 적히고, 리포트는 그 사람이 한 마디도 하지
 * 않았다고 집계한다.
 *
 * <p>정상 경로에서는 나올 수 없다. {@code RecordingFile.trackFile} 이 쓰기 시점에 상대 경로를 검증하므로, 이 예외는 그 검증을 거치지 않은 행이 있다는 신호다.
 */
public class TranscriptionSourceInvalidException extends BusinessException {

    public TranscriptionSourceInvalidException() {
        super(PostClassTranscriptionErrorCode.TRANSCRIPTION_SOURCE_INVALID);
    }
}
