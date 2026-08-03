package com.a105.zani.notification.infrastructure.mail;

import java.io.UnsupportedEncodingException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.a105.zani.notification.application.port.EmailMessage;
import com.a105.zani.notification.application.port.EmailSenderPort;

/**
 * spring-boot-starter-mail 의 {@link JavaMailSender} 로 HTML 이메일을 보낸다.
 *
 * <p>메일 설정(spring.mail.host)이 없으면 auto-config 가 JavaMailSender 빈을 만들지 않는다. 그런 환경(로컬·테스트)에서도 앱이 뜨도록 빈을
 * {@link ObjectProvider} 로 느슨히 주입하고, 실제로 없을 때는 발송 시점에 예외로 알린다 — consumer 가 실패로 기록하고 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class SmtpEmailSenderAdapter implements EmailSenderPort {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${notification.email.from:no-reply@zani.app}")
    private String from;

    @Override
    public void send(EmailMessage message) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new EmailDeliveryException("메일 발송기가 설정되지 않았습니다 (spring.mail.host 미설정)");
        }
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toAddress(message));
            helper.setSubject(message.subject());
            helper.setText(message.bodyHtml(), true);
            mailSender.send(mimeMessage);
        } catch (MailException | MessagingException | UnsupportedEncodingException exception) {
            throw new EmailDeliveryException("이메일 전송 실패: " + message.to(), exception);
        }
    }

    /** 수신자 표시 이름이 있으면 "이름 <이메일>" 로, 없으면 주소만. */
    private static InternetAddress toAddress(EmailMessage message)
            throws AddressException, UnsupportedEncodingException {
        if (message.toName() == null || message.toName().isBlank()) {
            return new InternetAddress(message.to());
        }
        return new InternetAddress(message.to(), message.toName(), "UTF-8");
    }
}
