package com.a105.zani.audioclip.application.port;

import java.time.Duration;

/**
 * 전사에 바로 넘길 수 있는 클립. 전사 작업(티켓 203)과의 계약이다.
 *
 * <p>파일을 만들지 않고 메모리에서 조립해 돌려준다. "전사 후 즉시 폐기"가 구조적으로 보장되고 고아 파일을 청소할 일도 없다.
 *
 * @param wav 16kHz 모노 WAV. 헤더가 붙어 있어 그대로 디코딩·전송할 수 있다
 * @param fromEpochMs 이 구간의 시작 벽시계 시각(무음 패딩으로 정렬돼 있다)
 * @param toEpochMs 이 구간의 끝 벽시계 시각
 * @param actual 실제로 확보된 길이. 요청한 window 보다 짧을 수 있다(수업 시작 직후 등). 전사·팁·쿨타임을 건너뛸지 판단하는 근거다
 */
public record AudioClip(byte[] wav, long fromEpochMs, long toEpochMs, Duration actual) {}
