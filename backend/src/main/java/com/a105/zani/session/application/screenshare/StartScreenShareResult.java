package com.a105.zani.session.application.screenshare;

/**
 * 화면 공유 시작 결과.
 *
 * @param participantId 공유를 시작한(또는 이어가는) 세션 참가자 ID. LiveKit identity {@code p-{participantId}}와 같은 식별자 공간이다
 */
public record StartScreenShareResult(long participantId) {}
