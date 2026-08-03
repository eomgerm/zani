package com.a105.zani.session.application.togglehand;

/**
 * @param raised 처리 후 손들기 상태
 * @param changed 실제로 바뀐 경우 true. 이미 같은 상태였으면 false 이며 이력을 남기지 않는다
 * @param rejectionReason 받아들이지 않은 사유 코드. 정상 처리면 null
 */
public record ToggleHandResult(boolean raised, boolean changed, String rejectionReason) {

    public static ToggleHandResult applied(boolean raised, boolean changed) {
        return new ToggleHandResult(raised, changed, null);
    }

    public static ToggleHandResult rejected(String reason) {
        return new ToggleHandResult(false, false, reason);
    }

    public boolean rejected() {
        return rejectionReason != null;
    }
}
