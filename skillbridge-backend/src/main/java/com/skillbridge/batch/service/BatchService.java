package com.skillbridge.batch.service;

import com.skillbridge.batch.dto.CreateBatchRequest;
import com.skillbridge.batch.dto.UpdateBatchRequest;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.exception.BadRequestException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.tenant.TenantGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creating and changing batches.
 *
 * <p>This used to live in {@code BatchController}, where three things went
 * wrong quietly (all fixed here, 2026-09-20):
 *
 * <ul>
 *   <li>A date that parsed as neither format was <b>logged and dropped</b>: the
 *       batch was created or updated with no start date and the caller was told
 *       201/200. Now it is a 400 naming the field and the formats.</li>
 *   <li>An update copied {@code request.name} whatever it was, so a partial body
 *       -- which is what the screen sends -- <b>blanked the name</b>.</li>
 *   <li>A status outside the five the database allows reached the CHECK
 *       constraint and came back as a conflict about a constraint name.</li>
 * </ul>
 *
 * <p>There was also no transaction: each repository call committed on its own.
 */
@Service
public class BatchService {

    /**
     * The five the database allows. {@code BatchStatusMatchesDatabaseTest} reads
     * {@code batches_status_check} and fails if these two lists ever differ.
     */
    public static final Set<String> STATUSES =
            new LinkedHashSet<>(Set.of("UPCOMING", "OPEN", "ACTIVE", "COMPLETED", "CANCELLED"));

    private static final String DEFAULT_STATUS = "UPCOMING";
    private static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final BatchRepository batches;
    private final CollegeRepository colleges;

    public BatchService(BatchRepository batches, CollegeRepository colleges) {
        this.batches = batches;
        this.colleges = colleges;
    }

    @Transactional
    public Batch create(CreateBatchRequest request, Long collegeId) {
        College college = colleges.findById(collegeId)
                .orElseThrow(() -> ResourceNotFoundException.of("College", collegeId));
        LocalDate start = date(request.startDate, "startDate");
        LocalDate end = date(request.endDate, "endDate");
        requireOrder(start, end);

        return batches.save(Batch.builder()
                .college(college)
                .name(request.name.trim())
                .description(request.description)
                .status(status(request.status, DEFAULT_STATUS))
                .startDate(start)
                .endDate(end)
                .build());
    }

    /** Only the fields the body carries are changed. */
    @Transactional
    public Batch update(Long batchId, UpdateBatchRequest request) {
        Batch batch = visible(batchId);
        if (request.name != null) {
            if (request.name.isBlank()) {
                throw new BadRequestException("Name cannot be blank");
            }
            batch.setName(request.name.trim());
        }
        if (request.description != null) {
            batch.setDescription(request.description);
        }
        if (request.status != null) {
            batch.setStatus(status(request.status, null));
        }
        if (request.startDate != null) {
            batch.setStartDate(date(request.startDate, "startDate"));
        }
        if (request.endDate != null) {
            batch.setEndDate(date(request.endDate, "endDate"));
        }
        requireOrder(batch.getStartDate(), batch.getEndDate());
        return batches.save(batch);
    }

    @Transactional
    public Batch updateStatus(Long batchId, String status) {
        Batch batch = visible(batchId);
        batch.setStatus(status(status, null));
        return batches.save(batch);
    }

    /**
     * A batch of another college is indistinguishable from one that does not
     * exist: a 403 would confirm the id.
     */
    private Batch visible(Long batchId) {
        return batches.findById(batchId)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));
    }

    private static String status(String status, String fallback) {
        if (status == null || status.isBlank()) {
            if (fallback == null) {
                throw new BadRequestException("Status is required. One of: " + String.join(", ", STATUSES));
            }
            return fallback;
        }
        String upper = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(upper)) {
            throw new BadRequestException("Unknown status \"" + status.trim() + "\". One of: "
                    + String.join(", ", STATUSES));
        }
        return upper;
    }

    /** Empty means "not given"; unparseable is an error, not a shrug. */
    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException iso) {
            try {
                return LocalDate.parse(trimmed, US);
            } catch (DateTimeParseException us) {
                throw new BadRequestException(field + " is not a date: \"" + trimmed
                        + "\". Use yyyy-MM-dd, for example 2026-09-20.");
            }
        }
    }

    private static void requireOrder(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BadRequestException("endDate cannot be before startDate");
        }
    }
}
