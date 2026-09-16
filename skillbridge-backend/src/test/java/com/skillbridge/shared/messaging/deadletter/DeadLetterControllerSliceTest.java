package com.skillbridge.shared.messaging.deadletter;

import com.skillbridge.common.audit.AuditAction;
import com.skillbridge.common.audit.AuditLogService;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.GlobalExceptionHandler;
import com.skillbridge.common.idempotency.IdempotencyService;
import com.skillbridge.testsupport.WebSliceSupport;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dead-letter endpoints' HTTP contract: who may call them, what they refuse,
 * what they hand the service, and what they write to the audit trail.
 *
 * <p>The audit assertions matter as much as the status codes. A replay re-executes
 * side effects, and "who replayed this, and was anyone refused" has to be
 * answerable from the audit log -- including for the attempts that failed.
 */
@WebMvcTest(
        controllers = DeadLetterController.class,
        // Same reason as ProgressControllerSliceTest: the application's servlet
        // filters need a database this slice does not have.
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = Filter.class))
@Import({WebSliceSupport.class, GlobalExceptionHandler.class})
class DeadLetterControllerSliceTest {

    private static final String BASE = "/api/v1/admin/dead-letters";
    private static final UUID REPLAY = UUID.fromString("0b0e7f1c-47a6-4a51-9a64-1f2e3d4c5b6a");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeadLetterService deadLetters;

    @MockitoBean
    private AuditLogService audit;

    /** Pulled in by IdempotencyWebConfig; see ProgressControllerSliceTest. */
    @MockitoBean
    private IdempotencyService idempotencyService;

    private static RequestPostProcessor systemAdmin() {
        return WebSliceSupport.as(1L, null, "SYSTEM_ADMIN");
    }

    // ---------------------------------------------------------------- who

    @Test
    @DisplayName("a system admin may list; the default view is PENDING, first page")
    void systemAdminLists() throws Exception {
        when(deadLetters.list(any(), any(), eq(0), eq(20))).thenReturn(PagedResponse.<DeadLetterDTO>builder()
                .items(List.of()).page(0).size(20).first(true).last(true).sort("UNSORTED").build());

        mvc.perform(get(BASE).with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        verify(deadLetters).list(DeadLetterStatus.PENDING, null, 0, 20);
    }

    @Test
    @DisplayName("a college admin reaches none of it, and the service is never called")
    void collegeAdminIsRefused() throws Exception {
        RequestPostProcessor collegeAdmin = WebSliceSupport.collegeAdmin(2L);

        mvc.perform(get(BASE).with(collegeAdmin)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/5").with(collegeAdmin)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/5/replay").with(collegeAdmin)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/5/discard").with(collegeAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/replay-batch").with(collegeAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dryRun\":false}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deadLetters);
    }

    @Test
    @DisplayName("an unknown status is a 400, not a 500")
    void badStatus() throws Exception {
        mvc.perform(get(BASE).param("status", "ALL").with(systemAdmin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deadLetters);
    }

    // ---------------------------------------------------------------- replay

    @Test
    @DisplayName("a replay is 202 with the new event id, and is audited with it")
    void replayIsAudited() throws Exception {
        when(deadLetters.replay(5L, 1L)).thenReturn(REPLAY);

        mvc.perform(post(BASE + "/5/replay").with(systemAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.replayEventId").value(REPLAY.toString()));

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.DEAD_LETTER_REPLAYED), eq("DeadLetterEvent"), eq(5L),
                eq(AuditAction.OUTCOME_SUCCESS), metadata.capture());
        assertThat(metadata.getValue()).contains(REPLAY.toString());
    }

    @Test
    @DisplayName("a refused replay keeps its status code and is audited as a failure")
    void refusedReplayIsAudited() throws Exception {
        when(deadLetters.replay(5L, 1L))
                .thenThrow(new ConflictException("DEAD_LETTER_ALREADY_RESOLVED", "Dead letter 5 is already REPLAYED"));

        mvc.perform(post(BASE + "/5/replay").with(systemAdmin()))
                .andExpect(status().isConflict());

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.DEAD_LETTER_REPLAYED), eq("DeadLetterEvent"), eq(5L),
                eq(AuditAction.OUTCOME_FAILURE), metadata.capture());
        assertThat(metadata.getValue()).contains("DEAD_LETTER_ALREADY_RESOLVED");
        verify(audit, never()).record(anyString(), anyString(), any(), eq(AuditAction.OUTCOME_SUCCESS), any());
    }

    // ---------------------------------------------------------------- discard

    @Test
    @DisplayName("a discard without a note is refused before the service sees it")
    void discardNeedsANote() throws Exception {
        for (String body : new String[]{"{}", "{\"note\":\"   \"}", "{\"note\":\"" + "x".repeat(501) + "\"}"}) {
            mvc.perform(post(BASE + "/5/discard").with(systemAdmin())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(deadLetters);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a discard passes the note through and audits it")
    void discardIsAudited() throws Exception {
        mvc.perform(post(BASE + "/5/discard").with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"test message\"}"))
                .andExpect(status().isOk());

        verify(deadLetters).discard(5L, 1L, "test message");
        verify(audit).record(eq(AuditAction.DEAD_LETTER_DISCARDED), eq("DeadLetterEvent"), eq(5L),
                eq(AuditAction.OUTCOME_SUCCESS), any());
    }

    // ---------------------------------------------------------------- batch

    @Test
    @DisplayName("a bulk replay that does not say dryRun is a dry run, and is not audited")
    void batchDefaultsToDryRun() throws Exception {
        when(deadLetters.replayBatch(any(), anyLong()))
                .thenReturn(new ReplayBatchResultDTO(true, 0, List.of(), List.of(), false));

        mvc.perform(post(BASE + "/replay-batch").with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventType\":\"SKILL_UPDATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));

        ArgumentCaptor<ReplayBatchRequest> request = ArgumentCaptor.forClass(ReplayBatchRequest.class);
        verify(deadLetters).replayBatch(request.capture(), eq(1L));
        assertThat(request.getValue().isDryRunRequested()).isTrue();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a real bulk replay is audited once, with what it replayed and skipped")
    void realBatchIsAudited() throws Exception {
        when(deadLetters.replayBatch(any(), anyLong())).thenReturn(new ReplayBatchResultDTO(false, 2,
                List.of(new ReplayBatchResultDTO.Replayed(7L, REPLAY)),
                List.of(new ReplayBatchResultDTO.Skipped(8L, DeadLetterService.NOT_JSON_OBJECT)), false));

        mvc.perform(post(BASE + "/replay-batch").with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[7,8],\"dryRun\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed[0].replayEventId").value(REPLAY.toString()))
                .andExpect(jsonPath("$.skipped[0].reason").value(DeadLetterService.NOT_JSON_OBJECT));

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.DEAD_LETTER_BATCH_REPLAYED), eq("DeadLetterEvent"), isNull(),
                eq(AuditAction.OUTCOME_SUCCESS), metadata.capture());
        assertThat(metadata.getValue()).contains(REPLAY.toString()).contains(DeadLetterService.NOT_JSON_OBJECT);
    }

    @Test
    @DisplayName("a bulk replay refuses ids and eventType together, and oversized selections")
    void batchValidation() throws Exception {
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(501, "1")) + "]";
        for (String body : new String[]{
                "{\"ids\":[1],\"eventType\":\"SKILL_UPDATED\",\"dryRun\":false}",
                "{\"limit\":501}",
                "{\"limit\":0}",
                "{\"ids\":" + tooMany + "}"}) {
            mvc.perform(post(BASE + "/replay-batch").with(systemAdmin())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(deadLetters);
    }
}
