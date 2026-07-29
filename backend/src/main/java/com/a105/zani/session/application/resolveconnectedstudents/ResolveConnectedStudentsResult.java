package com.a105.zani.session.application.resolveconnectedstudents;

import java.util.List;

/**
 * 지금 접속 중인 학생들.
 *
 * <p>강사는 담기지 않는다. 강사는 집계 대상이 아니고(§2), 강사 이탈은 presence 유예로 따로 다룬다.
 *
 * @param students 접속 중인 학생. 접속이 끊긴 학생은 없다
 */
public record ResolveConnectedStudentsResult(List<ConnectedStudent> students) {

    public ResolveConnectedStudentsResult {
        students = List.copyOf(students);
    }
}
