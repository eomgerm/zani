package com.a105.zani.notification.application.port;

/** 한 수신자에게 보낼 이메일 한 통. 본문은 HTML 이며 로그인 링크를 담는다. */
public record EmailMessage(String to, String toName, String subject, String bodyHtml) {}
