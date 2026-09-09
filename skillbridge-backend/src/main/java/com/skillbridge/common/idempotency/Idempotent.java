package com.skillbridge.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler as requiring an {@code Idempotency-Key} header.
 *
 * <p>Put it on a handler where a duplicate request does duplicate damage and
 * nothing else stops it. Most write endpoints here do not qualify, and saying
 * why is the point:
 *
 * <ul>
 *   <li>{@code applyToBatch} already returns the existing application on a
 *       retry, with a partial unique index as the backstop;</li>
 *   <li>approving or rejecting an enrollment request goes through
 *       {@code EnrollmentStatus.assertCanTransitionTo}, so a second attempt
 *       throws rather than double-applying;</li>
 *   <li>enrolling a student twice hits a uniqueness check and returns 409.</li>
 * </ul>
 *
 * <p>What is left is resource creation with no natural key: {@code batches},
 * {@code companies} and {@code student_projects} have <em>no unique constraint
 * of any kind</em>, so a double-clicked Create button leaves two identical rows
 * and nothing to distinguish them afterwards.
 *
 * <p><b>The header is required, not optional.</b> An optional idempotency key is
 * one clients forget to send, and a protection that is off by default protects
 * nobody. A request without it is rejected with 400 rather than quietly
 * proceeding unprotected.
 *
 * <p><b>JSON bodies only.</b> The interceptor hashes the raw request body to
 * detect a key reused with different content, which means buffering it.
 * Multipart uploads are deliberately out of scope: the servlet container parses
 * those from its own stream, so buffering the body underneath it is not
 * something to do quietly. {@code IdempotencyKeyFilter} skips multipart
 * requests, and {@code IdempotencyRulesTest} fails the build if this annotation
 * is put on a multipart handler.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
