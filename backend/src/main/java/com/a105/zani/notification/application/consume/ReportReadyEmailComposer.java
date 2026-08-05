package com.a105.zani.notification.application.consume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.a105.zani.notification.application.port.EmailMessage;
import com.a105.zani.notification.application.port.PendingNotification;

/**
 * 리포트 준비 알림 이메일 한 통을 만든다. 본문에는 세션 리포트로 가는 <b>로그인 링크</b>를 담는다 — 링크가 가리키는 페이지는 인증이 필요하므로, 받은 사람이 로그인해야 리포트를 본다. 수신자 이름은
 * HTML 이스케이프해 본문 주입을 막는다.
 */
@Component
public class ReportReadyEmailComposer {

    private final String appBaseUrl;

    public ReportReadyEmailComposer(@Value("${notification.email.app-base-url:https://zani.app}") String appBaseUrl) {
        // 끝 슬래시를 정리해 링크가 //sessions 로 겹치지 않게 한다.
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    public EmailMessage compose(PendingNotification notification) {
        String loginLink = appBaseUrl + "/my-lectures/" + notification.sessionId() + "/report";
        String subject = "[ZANI] 수업 리포트가 준비됐어요";
        String greeting = (notification.displayName() == null
                        || notification.displayName().isBlank())
                ? "안녕하세요,"
                : escape(notification.displayName()) + "님, 안녕하세요.";
        String bodyHtml = "<div style=\"font-family:sans-serif;line-height:1.6\">"
                + "<p>" + greeting + "</p>"
                + "<p>참여하신 수업의 리포트가 준비됐습니다. 아래 버튼으로 로그인해 확인하세요.</p>"
                + "<p><a href=\"" + escape(loginLink) + "\""
                + " style=\"display:inline-block;padding:10px 18px;background:#3b82f6;color:#fff;"
                + "text-decoration:none;border-radius:6px\">리포트 보러 가기</a></p>"
                + "<p style=\"color:#666;font-size:12px\">버튼이 열리지 않으면 다음 주소를 붙여 넣으세요:<br>"
                + escape(loginLink) + "</p>"
                + "<p>— ZANI</p>"
                + "</div>";
        return new EmailMessage(notification.email(), notification.displayName(), subject, bodyHtml);
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
