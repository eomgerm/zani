package com.a105.zani.session.application.getpostclasscontext;

/** 사후 분석이 쓸 수업 창(시작·종료)과 화자 별칭표를 돌려준다. 세션 도메인 밖에서는 이 계약으로만 읽는다. */
public interface GetPostClassContextUseCase {

    GetPostClassContextResult get(GetPostClassContextQuery query);
}
