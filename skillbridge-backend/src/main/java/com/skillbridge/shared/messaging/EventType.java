package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;

/**
 * Every event this backend publishes: its name on the wire, its routing key, and
 * the schema version of the payload it is published with.
 *
 * <p>The version here is the producer's half of {@code contracts/ai-events/versions.json}.
 * {@code AiEventContractTest} fails the build if they disagree, or if this version is
 * one the AI service does not accept. Raising it is a breaking change with a
 * deployment order — the consumer first — which {@code docs/EVENT_SCHEMA.md} walks
 * through; it is not an edit to make on its own.
 */
public enum EventType {

    /** A student added a skill or changed its proficiency. Payload: {@link SkillUpdated}. */
    SKILL_UPDATED(RabbitMQConfig.SKILL_UPDATED_KEY, 2),

    /** A student changed profile fields the analysis reads. Payload: {@link ProfileUpdated}. */
    PROFILE_UPDATED(RabbitMQConfig.PROFILE_UPDATED_KEY, 2);

    private final String routingKey;
    private final int schemaVersion;

    EventType(String routingKey, int schemaVersion) {
        this.routingKey = routingKey;
        this.schemaVersion = schemaVersion;
    }

    public String routingKey() {
        return routingKey;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** SKILL_UPDATED, schema version 2. Version 1 carried the skill id in {@code metadata}. */
    public record SkillUpdated(long studentId, long skillId) {
    }

    /** PROFILE_UPDATED, schema version 2. */
    public record ProfileUpdated(long studentId) {
    }
}
