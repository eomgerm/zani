package com.a105.zani.auth.application.port;

public record GoogleIdentity(String subject, String email, String name, String pictureUrl) {}
