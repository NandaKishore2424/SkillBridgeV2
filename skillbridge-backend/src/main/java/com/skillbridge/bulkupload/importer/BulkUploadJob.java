package com.skillbridge.bulkupload.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.invitation.InvitationMailer;
import com.skillbridge.shared.mail.MailDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Imports a checked file row by row, off the request thread.
 *
 * <p>Not transactional, on purpose: every write goes through
 * {@link RowImporter}, one transaction per row, and each invitation is mailed
 * only after its row has committed. A row that fails is recorded with a reason
 * the admin can act on and the import carries on.
 */
@Service
@Slf4j
public class BulkUploadJob {

    /** Heartbeat and counters every this many rows. */
    static final int PROGRESS_EVERY = 10;

    static final String EMAIL_NOT_SENT =
            "Account created, but the invitation email was not sent. Use \"Resend invitation\".";

    private final RowImporter rows;
    private final ImportRowMapper mapper;
    private final InvitationMailer mailer;
    private final ObjectMapper objectMapper;

    public BulkUploadJob(RowImporter rows, ImportRowMapper mapper, InvitationMailer mailer, ObjectMapper objectMapper) {
        this.rows = rows;
        this.mapper = mapper;
        this.mailer = mailer;
        this.objectMapper = objectMapper;
    }

    @Async("bulkUploadExecutor")
    public void run(Long uploadId, Long collegeId, CsvImportFile file) {
        Counters counters = new Counters();
        log.info("Import {} started: {} {} rows", uploadId, file.rowCount(), file.kind());
        try {
            file.forEachRow(row -> {
                importRow(uploadId, collegeId, file.kind(), row, counters);
                if (counters.total % PROGRESS_EVERY == 0) {
                    rows.progress(uploadId, counters.total, counters.succeeded, counters.failed);
                }
            });
            rows.finish(uploadId, counters.total, counters.succeeded, counters.failed, "COMPLETED", null);
            log.info("Import {} completed: {} imported, {} failed, {} not emailed",
                    uploadId, counters.succeeded, counters.failed, counters.notEmailed);
        } catch (RuntimeException e) {
            // Not a row problem -- those are caught per row. The database went away,
            // say. Rows already imported stay imported.
            String reference = reference();
            log.error("Import {} stopped after {} rows [{}]", uploadId, counters.total, reference, e);
            rows.finish(uploadId, counters.total, counters.succeeded, counters.failed, "FAILED",
                    "The import stopped after " + counters.total + " rows (reference " + reference + "). "
                            + "Rows already imported are kept; upload the file again to import the rest.");
        }
    }

    private void importRow(Long uploadId, Long collegeId, ImportKind kind, ImportRow row, Counters counters) {
        counters.total++;
        String json = toJson(row);
        ImportedAccount account;
        try {
            account = switch (kind) {
                case STUDENT -> rows.importStudent(uploadId, row.rowNumber(), mapper.student(row), collegeId, json);
                case TRAINER -> rows.importTrainer(uploadId, row.rowNumber(), mapper.trainer(row), collegeId, json);
            };
        } catch (RowRejectedException e) {
            fail(uploadId, row, json, e.getMessage(), counters);
            return;
        } catch (DataIntegrityViolationException e) {
            // Passed the existence checks, then lost a race to a concurrent
            // import or signup: the unique constraint is the real guard.
            fail(uploadId, row, json, "An account with this email or roll number already exists", counters);
            return;
        } catch (RuntimeException e) {
            String reference = reference();
            log.error("Import {} row {} failed unexpectedly [{}]", uploadId, row.rowNumber(), reference, e);
            fail(uploadId, row, json, "This row could not be imported (reference " + reference + ")", counters);
            return;
        }
        counters.succeeded++;

        try {
            mailer.send(account.invitation());
        } catch (MailDeliveryException e) {
            counters.notEmailed++;
            log.warn("Import {} row {}: invitation to {} not sent: {}",
                    uploadId, row.rowNumber(), account.invitation().email(), e.getMessage());
            rows.recordEmailFailure(account.resultId(), EMAIL_NOT_SENT);
        }
    }

    private void fail(Long uploadId, ImportRow row, String json, String reason, Counters counters) {
        counters.failed++;
        rows.recordFailure(uploadId, row.rowNumber(), json, reason);
    }

    private String toJson(ImportRow row) {
        try {
            return objectMapper.writeValueAsString(row.values());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String reference() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class Counters {
        int total;
        int succeeded;
        int failed;
        int notEmailed;
    }
}
