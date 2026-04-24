package com.skillbridge.shared.messaging;

/**
 * Represents a standardized event payload sent to the AI service via RabbitMQ.
 *
 * Design Decision: We use a flat, self-contained record instead of referencing
 * entity objects. This is because message payloads must be fully serializable,
 * and we never want to accidentally trigger lazy-loading of Hibernate-managed
 * entities while serializing to JSON outside of a transaction boundary.
 */
public record AIEvent(
        String eventType,     // e.g. "SKILL_UPDATED", "PROFILE_UPDATED"
        Long   studentId,     // the student this event is about
        Long   collegeId,     // tenant discriminator - the AI service needs this for scoping
        Object metadata       // optional extra data (e.g., the specific skillId changed)
) {}
