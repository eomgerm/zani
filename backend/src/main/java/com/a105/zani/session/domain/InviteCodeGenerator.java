package com.a105.zani.session.domain;

import java.security.SecureRandom;

import com.a105.zani.session.domain.model.InviteCode;

/** 초대 코드를 만든다. 학생이 눈으로 읽고 옮겨 적는 값이라 서로 헷갈리는 글자를 뺀 알파벳을 쓴다({@link InviteCode#GENERATION_ALPHABET}). */
public class InviteCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        String alphabet = InviteCode.GENERATION_ALPHABET;
        StringBuilder builder = new StringBuilder(InviteCode.LENGTH);
        for (int i = 0; i < InviteCode.LENGTH; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }
}
