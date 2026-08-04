package com.a105.zani.postclass.application.port;

import java.nio.file.Path;
import java.util.List;

import com.a105.zani.postclass.application.exception.AudioChunkFailedException;

/**
 * 원본 트랙을 GMS 업로드 한도 안으로 자른다(S15P11A105-247).
 *
 * <p>재인코딩하지 않는다. LiveKit Track Egress 가 남기는 OGG/Opus 는 GMS whisper-1 이 그대로 받으므로(실측) 컨테이너만 다시 묶는다. 디코딩·인코딩을 끼우면 이중 손실이
 * 생기고 라이브 수업을 서비스하는 호스트의 CPU 를 쓴다.
 *
 * <p>구현이 지켜야 하는 것.
 *
 * <ul>
 *   <li>원본을 수정하지 않는다 — 읽기 전용 마운트에 있다
 *   <li>산출물은 넘겨받은 작업 디렉터리 안에만 만든다
 *   <li>어떤 DB 트랜잭션도 이 호출을 감싸지 않는다. 외부 프로세스를 기다리는 동안 행 잠금을 붙잡으면 다른 단계의 전이가 잠금 대기로 실패한다
 * </ul>
 */
public interface AudioChunkPort {

    /**
     * @param source 원본 트랙 파일(읽기 전용)
     * @param workDir 청크를 만들 디렉터리. 만들고 지우는 것은 호출자 책임이다
     * @return 순번 오름차순 청크 목록. 원본이 목표 길이보다 짧으면 한 개
     * @throws AudioChunkFailedException 분할 실패 또는 산출물 검증 실패
     */
    List<AudioChunk> split(Path source, Path workDir);
}
