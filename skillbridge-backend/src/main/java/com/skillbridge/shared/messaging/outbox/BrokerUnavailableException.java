package com.skillbridge.shared.messaging.outbox;

/**
 * The broker did not take an event, for a reason that is the broker's and not the event's.
 *
 * <p>Thrown by {@link OutboxRelay} when a send fails, a confirm does not arrive in
 * time, or the broker nacks — it is down, unreachable, restarting, or refusing
 * publishes because the queue is full. None of that says anything about the event,
 * so none of it counts toward the attempts that make an event DEAD; see
 * {@link OutboxRelay} for why that distinction is the whole difference between
 * riding out an outage and losing the events written during it.
 *
 * <p>The counterpart, an unroutable event, is the event's own fault and is thrown as
 * a plain {@code AmqpException}.
 */
class BrokerUnavailableException extends RuntimeException {

    BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
