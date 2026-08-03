package com.a105.zani.quiz.application.port;

/** 문항의 보기 하나. {@code correct} 는 채점용이며, 제출 전 응답에 실리면 안 된다 — 노출 여부는 각 유스케이스 Result 가 거른다. */
public record QuizOptionSnapshot(Long optionId, String text, int order, boolean correct) {}
