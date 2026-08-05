package com.a105.zani.postclass.application.exception;

import com.a105.zani.common.error.BusinessException;

/**
 * 다시 분할한 청크 경계가 기록된 체크포인트와 다르다.
 *
 * <p>이것을 잡지 않으면 시간축이 조용히 밀린다. 재시도할 때는 임시 파일이 없어 원본을 다시 자르는데, 그 사이 청크 길이 설정이 바뀌었거나 ffmpeg 동작이 달라지면 새 청크의 구간이 저장된 값과
 * 어긋난다. 그러면 "DB 에 있는 옛 오프셋 + 새 청크의 상대 시각" 이라는 잘못된 조합으로 절대 시각이 만들어진다.
 *
 * <p>재시도하지 않는다. 같은 설정으로 다시 자르면 같은 결과가 나오므로 재시도는 예산만 태운다. 경계가 왜 달라졌는지는 사람이 봐야 한다 — 설정 변경이라면 그 세션의 체크포인트를 지우고 처음부터 다시 하는
 * 것이 맞다.
 */
public class TranscriptionChunkBoundaryMismatchException extends BusinessException {

    public TranscriptionChunkBoundaryMismatchException() {
        super(PostClassTranscriptionErrorCode.CHUNK_BOUNDARY_MISMATCH);
    }
}
