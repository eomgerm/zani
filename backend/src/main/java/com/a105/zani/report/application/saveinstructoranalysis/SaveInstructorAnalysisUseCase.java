package com.a105.zani.report.application.saveinstructoranalysis;

import java.util.Optional;

public interface SaveInstructorAnalysisUseCase {

    /** 저장했으면 리포트 ID, 다른 실행이 먼저 저장했으면 빈 값. */
    Optional<Long> save(SaveInstructorAnalysisCommand command);
}
