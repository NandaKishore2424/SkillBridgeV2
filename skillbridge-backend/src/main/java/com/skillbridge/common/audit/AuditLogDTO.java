package com.skillbridge.common.audit;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One audit row as returned by the API. */
@Data
@Builder
public class AuditLogDTO {

    private Long id;
    private LocalDateTime occurredAt;
    private Long actorUserId;
    private String actorEmail;
    private String action;
    private String resourceType;
    private String resourceId;
    private String outcome;
    private String ipAddress;
    private String metadata;

    /**
     * {@code userAgent} and {@code collegeId} are deliberately omitted.
     *
     * <p>The user agent is long, low-value in a listing, and echoing a
     * client-supplied string back into a response is a needless XSS foothold for
     * whatever renders it. The college id carries no information: a
     * COLLEGE_ADMIN only ever sees their own.
     */
    public static AuditLogDTO from(AuditLog entry) {
        return AuditLogDTO.builder()
                .id(entry.getId())
                .occurredAt(entry.getOccurredAt())
                .actorUserId(entry.getActorUserId())
                .actorEmail(entry.getActorEmail())
                .action(entry.getAction())
                .resourceType(entry.getResourceType())
                .resourceId(entry.getResourceId())
                .outcome(entry.getOutcome())
                .ipAddress(entry.getIpAddress())
                .metadata(entry.getMetadata())
                .build();
    }
}
