package com.skillbridge.common.audit;

/**
 * The action names written to {@code audit_log.action}.
 *
 * <p>Constants rather than an enum: the column is a {@code VARCHAR} on purpose,
 * so a future action can be recorded without a schema change, and historic rows
 * keep their value even if the constant is later removed from the code. An enum
 * would make deserialising an unknown historic value throw.
 */
public final class AuditAction {

    private AuditAction() {
    }

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";
    public static final String TOKEN_REFRESHED = "TOKEN_REFRESHED";

    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String FIRST_LOGIN_COMPLETED = "FIRST_LOGIN_COMPLETED";
    public static final String INVITATION_RESENT = "INVITATION_RESENT";

    public static final String ENROLLMENT_APPROVED = "ENROLLMENT_APPROVED";
    public static final String ENROLLMENT_REJECTED = "ENROLLMENT_REJECTED";
    public static final String STUDENT_ENROLLED = "STUDENT_ENROLLED";
    public static final String STUDENT_UNENROLLED = "STUDENT_UNENROLLED";

    public static final String BULK_UPLOAD_STARTED = "BULK_UPLOAD_STARTED";

    /** Outcome for a rejected action. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_DENIED = "DENIED";
}
