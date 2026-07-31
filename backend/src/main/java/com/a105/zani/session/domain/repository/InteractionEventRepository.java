package com.a105.zani.session.domain.repository;

import com.a105.zani.session.domain.model.InteractionEvent;

public interface InteractionEventRepository {

    InteractionEvent save(InteractionEvent interactionEvent);
}
