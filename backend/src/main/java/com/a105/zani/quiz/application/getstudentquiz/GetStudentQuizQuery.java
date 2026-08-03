package com.a105.zani.quiz.application.getstudentquiz;

/** 본인 퀴즈 조회 입력. memberId 는 인증 주체에서, sessionId 는 경로에서 온다. */
public record GetStudentQuizQuery(Long sessionId, Long memberId) {}
