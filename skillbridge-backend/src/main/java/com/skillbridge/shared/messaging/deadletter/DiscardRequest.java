package com.skillbridge.shared.messaging.deadletter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a dead letter is not worth replaying. Required: a discard with no reason is
 * indistinguishable, a month later, from a mistake.
 */
public record DiscardRequest(@NotBlank @Size(max = 500) String note) {
}
