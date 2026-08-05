package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getinstructorclip.GetInstructorClipResult;
import com.a105.zani.report.application.getinstructorclip.InstructorClipQueryPort;
import com.a105.zani.report.infrastructure.persistence.repository.SessionReportJpaRepository;

/**
 * 강사 수업 클립이 읽는 두 가지 — 공통 리포트의 게시 여부와 세션 전사.
 *
 * <p>전사 펼치기 규칙(JSON_TABLE·실명 조인·결측 처리)은 {@link SessionTranscriptQuery} 가 소유하고 학생 리포트 조회와 공유한다.
 */
@Component
@RequiredArgsConstructor
public class InstructorClipQueryAdapter implements InstructorClipQueryPort {

    private final SessionReportJpaRepository sessionReportRepository;
    private final SessionTranscriptQuery transcriptQuery;

    @Override
    public boolean sessionReportPublished(long sessionId) {
        return sessionReportRepository.existsBySessionIdAndPublishedAtIsNotNull(sessionId);
    }

    @Override
    public List<GetInstructorClipResult.TranscriptSegment> transcript(long sessionId) {
        return transcriptQuery.segments(sessionId).stream()
                .map(segment -> new GetInstructorClipResult.TranscriptSegment(
                        segment.startSeconds(), segment.endSeconds(), segment.speakerName(), segment.text()))
                .toList();
    }
}
