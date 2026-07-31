package com.a105.zani.session.application.sendchatmessage;

public interface SendChatMessageUseCase {

    SendChatMessageResult send(SendChatMessageCommand command);
}
