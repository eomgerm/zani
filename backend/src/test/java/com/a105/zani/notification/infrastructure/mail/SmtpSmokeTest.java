package com.a105.zani.notification.infrastructure.mail;

import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import com.a105.zani.notification.application.consume.ReportReadyEmailComposer;
import com.a105.zani.notification.application.port.EmailMessage;
import com.a105.zani.notification.application.port.PendingNotification;

/**
 * SMTP 발송 경로를 실제로 한 번 태워 보는 수동 스모크 테스트. DB·스프링 컨텍스트 없이 실제 {@link SmtpEmailSenderAdapter} 와
 * {@link ReportReadyEmailComposer} 를 그대로 써서 진짜 이메일 한 통을 보낸다.
 *
 * <p>{@code NOTIFICATION_SMTP_SMOKE=true} 일 때만 실행된다(평소·CI 에서는 건너뜀). 환경변수:
 *
 * <ul>
 *   <li>SPRING_MAIL_HOST / SPRING_MAIL_PORT / SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD — SMTP 접속
 *   <li>NOTIFICATION_TEST_TO — 받는 주소(없으면 SPRING_MAIL_USERNAME 즉 자기 자신에게)
 *   <li>NOTIFICATION_EMAIL_FROM / NOTIFICATION_APP_BASE_URL — 없으면 기본값
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "NOTIFICATION_SMTP_SMOKE", matches = "true")
class SmtpSmokeTest {

    @Test
    void sendsOneRealReportReadyEmail() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(requireEnv("SPRING_MAIL_HOST"));
        mailSender.setPort(Integer.parseInt(envOr("SPRING_MAIL_PORT", "587")));
        mailSender.setUsername(requireEnv("SPRING_MAIL_USERNAME"));
        mailSender.setPassword(requireEnv("SPRING_MAIL_PASSWORD"));
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        SmtpEmailSenderAdapter adapter = new SmtpEmailSenderAdapter(fixedProvider(mailSender));
        ReflectionTestUtils.setField(
                adapter, "from", envOr("NOTIFICATION_EMAIL_FROM", requireEnv("SPRING_MAIL_USERNAME")));

        ReportReadyEmailComposer composer =
                new ReportReadyEmailComposer(envOr("NOTIFICATION_APP_BASE_URL", "http://localhost:5173"));

        String to = envOr("NOTIFICATION_TEST_TO", requireEnv("SPRING_MAIL_USERNAME"));
        EmailMessage message = composer.compose(new PendingNotification(1L, 500L, 999L, to, "테스트 수신자", 0));

        adapter.send(message);
        System.out.println("[SmtpSmokeTest] 발송 완료 → " + to);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("환경변수 " + name + " 가 필요합니다");
        }
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** 스프링 컨텍스트 없이 어댑터에 주입할, 항상 같은 발송기를 돌려주는 ObjectProvider. */
    private static ObjectProvider<JavaMailSender> fixedProvider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject(Object... args) {
                return sender;
            }

            @Override
            public JavaMailSender getObject() {
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return sender;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return sender;
            }

            @Override
            public Stream<JavaMailSender> stream() {
                return Stream.of(sender);
            }
        };
    }
}
