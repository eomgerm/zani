package com.a105.zani.session.application.getsessionlist;

import java.util.List;

public interface GetSessionListUseCase {

    List<SessionSummaryResult> getList(GetSessionListQuery query);
}
