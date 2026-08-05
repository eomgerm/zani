package com.a105.zani.common.infrastructure.persistence;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import com.a105.zani.attention.infrastructure.persistence.entity.AttentionEventJpaEntity;
import com.a105.zani.attention.infrastructure.persistence.entity.CheckPromptEvidenceJpaEntity;
import com.a105.zani.attention.infrastructure.persistence.entity.CheckPromptJpaEntity;
import com.a105.zani.attention.infrastructure.persistence.entity.GroupAlertJpaEntity;
import com.a105.zani.attention.infrastructure.persistence.entity.GroupAlertResponseCountJpaEntity;
import com.a105.zani.member.infrastructure.persistence.entity.MemberJpaEntity;
import com.a105.zani.postclass.infrastructure.persistence.entity.InstructorNoteJpaEntity;
import com.a105.zani.postclass.infrastructure.persistence.entity.PipelineJobJpaEntity;
import com.a105.zani.postclass.infrastructure.persistence.entity.TranscriptJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizAnswerJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizOptionJpaEntity;
import com.a105.zani.quiz.infrastructure.persistence.entity.QuizQuestionJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingFileJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingOutboxJpaEntity;
import com.a105.zani.recording.infrastructure.persistence.entity.RecordingWebhookEventJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportInsightJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.InstructorReportScoreJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.ReviewRecommendationJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;
import com.a105.zani.report.infrastructure.persistence.entity.StudentReportJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.ChatMessageJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.InteractionEventJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionParticipantJpaEntity;
import com.a105.zani.session.infrastructure.persistence.entity.SessionStatusChangeJpaEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaEntityMappingTest {

    private static final Map<Class<?>, String> ENTITY_TABLES = Map.ofEntries(
            Map.entry(MemberJpaEntity.class, "members"),
            Map.entry(SessionJpaEntity.class, "sessions"),
            Map.entry(SessionParticipantJpaEntity.class, "session_participants"),
            Map.entry(SessionStatusChangeJpaEntity.class, "session_status_changes"),
            Map.entry(InstructorNoteJpaEntity.class, "instructor_notes"),
            Map.entry(PipelineJobJpaEntity.class, "pipeline_jobs"),
            Map.entry(ChatMessageJpaEntity.class, "chat_messages"),
            Map.entry(InteractionEventJpaEntity.class, "interaction_events"),
            Map.entry(AttentionEventJpaEntity.class, "attention_events"),
            Map.entry(CheckPromptJpaEntity.class, "check_prompts"),
            Map.entry(CheckPromptEvidenceJpaEntity.class, "check_prompt_evidences"),
            Map.entry(GroupAlertJpaEntity.class, "group_alerts"),
            Map.entry(GroupAlertResponseCountJpaEntity.class, "group_alert_response_counts"),
            Map.entry(SessionReportJpaEntity.class, "session_reports"),
            Map.entry(StudentReportJpaEntity.class, "student_reports"),
            Map.entry(InstructorReportJpaEntity.class, "instructor_reports"),
            Map.entry(InstructorReportScoreJpaEntity.class, "instructor_report_scores"),
            Map.entry(InstructorReportInsightJpaEntity.class, "instructor_report_insights"),
            Map.entry(SessionSectionJpaEntity.class, "session_sections"),
            Map.entry(ReviewRecommendationJpaEntity.class, "review_recommendations"),
            Map.entry(RecordingJpaEntity.class, "recordings"),
            Map.entry(RecordingOutboxJpaEntity.class, "recording_outbox"),
            Map.entry(RecordingWebhookEventJpaEntity.class, "recording_webhook_events"),
            Map.entry(RecordingFileJpaEntity.class, "recording_files"),
            Map.entry(TranscriptJpaEntity.class, "transcripts"),
            Map.entry(QuizJpaEntity.class, "quizzes"),
            Map.entry(QuizQuestionJpaEntity.class, "quiz_questions"),
            Map.entry(QuizOptionJpaEntity.class, "quiz_options"),
            Map.entry(QuizAnswerJpaEntity.class, "quiz_answers"));

    private static final Set<Class<?>> CREATED_ONLY_ENTITIES = Set.of(
            SessionStatusChangeJpaEntity.class,
            ChatMessageJpaEntity.class,
            InteractionEventJpaEntity.class,
            AttentionEventJpaEntity.class,
            CheckPromptEvidenceJpaEntity.class,
            GroupAlertJpaEntity.class,
            GroupAlertResponseCountJpaEntity.class,
            RecordingFileJpaEntity.class);

    private static final Set<Class<?>> SOFT_DELETABLE_ENTITIES = Set.of(MemberJpaEntity.class, SessionJpaEntity.class);

    private static final Set<Class<? extends Annotation>> PROHIBITED_RELATIONSHIP_ANNOTATIONS =
            Set.of(OneToMany.class, OneToOne.class, ManyToMany.class);

    private static final List<AssociationExpectation> ASSOCIATIONS = List.of(
            simple(GroupAlertJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(
                    GroupAlertResponseCountJpaEntity.class,
                    "groupAlert",
                    GroupAlertJpaEntity.class,
                    false,
                    "group_alert_id"),
            simple(
                    CheckPromptEvidenceJpaEntity.class,
                    "checkPrompt",
                    CheckPromptJpaEntity.class,
                    false,
                    "check_prompt_id"),
            simple(
                    CheckPromptEvidenceJpaEntity.class,
                    "attentionEvent",
                    AttentionEventJpaEntity.class,
                    true,
                    "attention_event_id"),
            simple(
                    CheckPromptEvidenceJpaEntity.class,
                    "interactionEvent",
                    InteractionEventJpaEntity.class,
                    true,
                    "interaction_event_id"),
            composite(
                    InstructorNoteJpaEntity.class,
                    "instructorParticipant",
                    SessionParticipantJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("instructor_participant_id", "id")),
            simple(SessionStatusChangeJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            composite(
                    StudentReportJpaEntity.class,
                    "sessionParticipant",
                    SessionParticipantJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("session_participant_id", "id")),
            simple(
                    ReviewRecommendationJpaEntity.class,
                    "studentReport",
                    StudentReportJpaEntity.class,
                    false,
                    "student_report_id"),
            composite(
                    AttentionEventJpaEntity.class,
                    "sessionParticipant",
                    SessionParticipantJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("session_participant_id", "id")),
            simple(SessionJpaEntity.class, "hostMember", MemberJpaEntity.class, false, "host_member_id"),
            simple(SessionReportJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(SessionSectionJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(InstructorReportJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(
                    InstructorReportScoreJpaEntity.class,
                    "instructorReport",
                    InstructorReportJpaEntity.class,
                    false,
                    "instructor_report_id"),
            simple(
                    InstructorReportInsightJpaEntity.class,
                    "instructorReport",
                    InstructorReportJpaEntity.class,
                    false,
                    "instructor_report_id"),
            composite(
                    ChatMessageJpaEntity.class,
                    "senderParticipant",
                    SessionParticipantJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("sender_participant_id", "id")),
            composite(
                    ChatMessageJpaEntity.class,
                    "recipientParticipant",
                    SessionParticipantJpaEntity.class,
                    true,
                    join("session_id", "session_id"),
                    join("recipient_participant_id", "id")),
            simple(InteractionEventJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            composite(
                    InteractionEventJpaEntity.class,
                    "actorParticipant",
                    SessionParticipantJpaEntity.class,
                    true,
                    join("session_id", "session_id"),
                    join("actor_participant_id", "id")),
            simple(TranscriptJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(QuizJpaEntity.class, "studentReport", StudentReportJpaEntity.class, false, "student_report_id"),
            simple(QuizQuestionJpaEntity.class, "quiz", QuizJpaEntity.class, false, "quiz_id"),
            simple(QuizOptionJpaEntity.class, "quizQuestion", QuizQuestionJpaEntity.class, false, "quiz_question_id"),
            composite(
                    QuizAnswerJpaEntity.class,
                    "selectedQuizOption",
                    QuizOptionJpaEntity.class,
                    false,
                    join("quiz_question_id", "quiz_question_id"),
                    join("selected_quiz_option_id", "id")),
            composite(
                    RecordingFileJpaEntity.class,
                    "recording",
                    RecordingJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("recording_id", "id")),
            composite(
                    RecordingFileJpaEntity.class,
                    "sessionParticipant",
                    SessionParticipantJpaEntity.class,
                    true,
                    join("session_id", "session_id"),
                    join("session_participant_id", "id")),
            composite(
                    CheckPromptJpaEntity.class,
                    "sessionParticipant",
                    SessionParticipantJpaEntity.class,
                    false,
                    join("session_id", "session_id"),
                    join("session_participant_id", "id")),
            simple(SessionParticipantJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"),
            simple(SessionParticipantJpaEntity.class, "member", MemberJpaEntity.class, false, "member_id"),
            simple(RecordingJpaEntity.class, "session", SessionJpaEntity.class, false, "session_id"));

    @Test
    void mapsEveryDdlTableToAnExplicitJpaEntity() {
        assertEquals(29, ENTITY_TABLES.size());

        ENTITY_TABLES.forEach((entityClass, expectedTable) -> {
            assertNotNull(entityClass.getAnnotation(Entity.class), entityClass.getSimpleName());

            Table table = entityClass.getAnnotation(Table.class);
            assertNotNull(table, entityClass.getSimpleName());
            assertEquals(expectedTable, table.name(), entityClass.getSimpleName());

            long idCount = Set.of(entityClass.getDeclaredFields()).stream()
                    .filter(field -> field.isAnnotationPresent(Id.class))
                    .count();
            assertEquals(1, idCount, entityClass.getSimpleName());
        });
    }

    @Test
    void usesExternallyAssignedIdsAndNoParentOrCollectionRelationships() {
        ENTITY_TABLES.keySet().forEach(entityClass -> {
            for (Field field : entityClass.getDeclaredFields()) {
                assertNull(field.getAnnotation(GeneratedValue.class), fieldDescription(entityClass, field));
                PROHIBITED_RELATIONSHIP_ANNOTATIONS.forEach(annotation ->
                        assertNull(field.getAnnotation(annotation), fieldDescription(entityClass, field)));
            }
        });
    }

    @Test
    void mapsEveryDdlForeignKeyAsReadOnlyLazyManyToOne() throws NoSuchFieldException {
        assertEquals(31, ASSOCIATIONS.size());
        assertEquals(31, countManyToOneFields());

        for (AssociationExpectation expectation : ASSOCIATIONS) {
            Field field = expectation.owner().getDeclaredField(expectation.fieldName());
            ManyToOne association = field.getAnnotation(ManyToOne.class);
            assertNotNull(association, fieldDescription(expectation.owner(), field));
            assertEquals(FetchType.LAZY, association.fetch(), fieldDescription(expectation.owner(), field));
            assertEquals(expectation.optional(), association.optional(), fieldDescription(expectation.owner(), field));
            assertEquals(expectation.target(), field.getType(), fieldDescription(expectation.owner(), field));
            assertEquals(0, association.cascade().length, fieldDescription(expectation.owner(), field));
            assertReadOnlyJoins(field, expectation.joins());
        }
    }

    @Test
    void declaresExplicitColumnMappingForEveryPersistentField() {
        ENTITY_TABLES.keySet().forEach(entityClass -> {
            for (Field field : entityClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(ManyToOne.class)) {
                    continue;
                }

                Column column = field.getAnnotation(Column.class);
                assertNotNull(column, fieldDescription(entityClass, field));
                assertFalse(column.name().isBlank(), fieldDescription(entityClass, field));
            }
        });
    }

    @Test
    void appliesAuditColumnsOnlyToTheAgreedEntityScopes() {
        ENTITY_TABLES.keySet().forEach(entityClass -> {
            assertTrue(BaseCreatedJpaEntity.class.isAssignableFrom(entityClass), entityClass.getSimpleName());

            if (CREATED_ONLY_ENTITIES.contains(entityClass)) {
                assertEquals(BaseCreatedJpaEntity.class, entityClass.getSuperclass(), entityClass.getSimpleName());
            } else if (SOFT_DELETABLE_ENTITIES.contains(entityClass)) {
                assertEquals(
                        BaseSoftDeletableJpaEntity.class, entityClass.getSuperclass(), entityClass.getSimpleName());
            } else {
                assertEquals(BaseJpaEntity.class, entityClass.getSuperclass(), entityClass.getSimpleName());
            }
        });
    }

    @Test
    void mapsAuditFieldsToMicrosecondUtcReadyColumns() throws NoSuchFieldException {
        Field createdAt = BaseCreatedJpaEntity.class.getDeclaredField("createdAt");
        assertNotNull(createdAt.getAnnotation(CreatedDate.class));
        assertAuditColumn(createdAt, "created_at", false);

        Field updatedAt = BaseJpaEntity.class.getDeclaredField("updatedAt");
        assertNotNull(updatedAt.getAnnotation(LastModifiedDate.class));
        assertAuditColumn(updatedAt, "updated_at", true);

        Field deletedAt = BaseSoftDeletableJpaEntity.class.getDeclaredField("deletedAt");
        Column deletedAtColumn = deletedAt.getAnnotation(Column.class);
        assertNotNull(deletedAtColumn);
        assertEquals("deleted_at", deletedAtColumn.name());
        assertEquals("DATETIME(6)", deletedAtColumn.columnDefinition());
    }

    private static void assertAuditColumn(Field field, String expectedName, boolean expectedUpdatable) {
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals(expectedName, column.name());
        assertFalse(column.nullable());
        assertEquals(expectedUpdatable, column.updatable());
        assertEquals("DATETIME(6)", column.columnDefinition());
    }

    private static long countManyToOneFields() {
        return ENTITY_TABLES.keySet().stream()
                .flatMap(entityClass -> Arrays.stream(entityClass.getDeclaredFields()))
                .filter(field -> field.isAnnotationPresent(ManyToOne.class))
                .count();
    }

    private static void assertReadOnlyJoins(Field field, List<JoinExpectation> expectedJoins) {
        List<JoinColumn> actualJoins;
        JoinColumns joinColumns = field.getAnnotation(JoinColumns.class);
        if (joinColumns != null) {
            actualJoins = List.of(joinColumns.value());
        } else {
            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            assertNotNull(joinColumn, field.getName());
            actualJoins = List.of(joinColumn);
        }

        assertEquals(expectedJoins.size(), actualJoins.size(), field.getName());
        for (int index = 0; index < expectedJoins.size(); index++) {
            JoinExpectation expected = expectedJoins.get(index);
            JoinColumn actual = actualJoins.get(index);
            assertEquals(expected.name(), actual.name(), field.getName());
            assertEquals(expected.referencedColumnName(), actual.referencedColumnName(), field.getName());
            assertFalse(actual.insertable(), field.getName());
            assertFalse(actual.updatable(), field.getName());
        }
    }

    private static AssociationExpectation simple(
            Class<?> owner, String fieldName, Class<?> target, boolean optional, String joinColumn) {
        return composite(owner, fieldName, target, optional, join(joinColumn, "id"));
    }

    private static AssociationExpectation composite(
            Class<?> owner, String fieldName, Class<?> target, boolean optional, JoinExpectation... joins) {
        return new AssociationExpectation(owner, fieldName, target, optional, List.of(joins));
    }

    private static JoinExpectation join(String name, String referencedColumnName) {
        return new JoinExpectation(name, referencedColumnName);
    }

    private static String fieldDescription(Class<?> entityClass, Field field) {
        return entityClass.getSimpleName() + "." + field.getName();
    }

    private record JoinExpectation(String name, String referencedColumnName) {}

    private record AssociationExpectation(
            Class<?> owner, String fieldName, Class<?> target, boolean optional, List<JoinExpectation> joins) {}
}
