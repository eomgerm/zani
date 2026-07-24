package com.a105.zani.session.application.presence;

public interface RecordPresenceUseCase {

    PresenceResult record(RecordPresenceCommand command);
}
