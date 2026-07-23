package com.a105.zani.session.application.get;

import java.util.List;

public interface GetSessionListUseCase {

    List<SessionSummaryResult> getList(GetSessionListQuery query);
}
