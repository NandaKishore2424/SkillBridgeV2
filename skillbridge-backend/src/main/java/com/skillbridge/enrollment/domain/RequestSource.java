package com.skillbridge.enrollment.domain;

/**
 * Who initiated an enrollment request.
 *
 * <p>The table was originally modelled for one case only — a trainer asking an
 * admin to add a student — which is why {@code trainer_id} was NOT NULL. Once a
 * student can apply for themselves, the row needs to say which kind of request
 * it is, because the two have different required fields and different reviewers.
 */
public enum RequestSource {

    /** A trainer asked for a student to be added to or removed from a batch. */
    TRAINER_REQUEST,

    /** A student applied to a batch themselves. No trainer is involved. */
    STUDENT_APPLICATION,

    /** An admin enrolled someone directly; the row exists for the audit trail. */
    ADMIN_DIRECT
}
