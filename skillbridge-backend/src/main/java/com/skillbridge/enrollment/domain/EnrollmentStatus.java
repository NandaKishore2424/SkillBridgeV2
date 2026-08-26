package com.skillbridge.enrollment.domain;

import com.skillbridge.common.exception.ConflictException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an enrollment request.
 *
 * <p>Transitions are declared here rather than checked ad hoc at each call site.
 * Two properties follow. An illegal transition becomes impossible to express
 * rather than merely unlikely; and adding a state means editing one table, after
 * which the compiler finds every switch that needs a new branch.
 *
 * <p>The previous model had three states — PENDING, APPROVED, REJECTED — and no
 * transition rules at all, so {@code approve()} on an already-rejected request
 * silently succeeded and created a duplicate enrollment.
 */
public enum EnrollmentStatus {

    /** Applied or requested; awaiting review. */
    PENDING {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.of(APPROVED, REJECTED, WITHDRAWN, EXPIRED);
        }
    },

    /** Approved — an {@code Enrollment} row now exists. */
    APPROVED {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.of(CANCELLED);
        }
    },

    /** Declined by an admin, with a reason. */
    REJECTED {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.noneOf(EnrollmentStatus.class);
        }
    },

    /** The applicant withdrew before anyone reviewed it. */
    WITHDRAWN {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.noneOf(EnrollmentStatus.class);
        }
    },

    /** The batch started before anyone reviewed it. Set by a scheduled sweep. */
    EXPIRED {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.noneOf(EnrollmentStatus.class);
        }
    },

    /** Enrollment revoked after approval. */
    CANCELLED {
        @Override
        public Set<EnrollmentStatus> allowedNext() {
            return EnumSet.noneOf(EnrollmentStatus.class);
        }
    };

    public abstract Set<EnrollmentStatus> allowedNext();

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    public boolean canTransitionTo(EnrollmentStatus target) {
        return allowedNext().contains(target);
    }

    /**
     * Guard for a state change.
     *
     * <p>The message names both states and lists what would have been legal —
     * worth the extra characters, because "invalid transition" in a bug report
     * tells whoever reads it nothing at all.
     */
    public void assertCanTransitionTo(EnrollmentStatus target) {
        if (!canTransitionTo(target)) {
            throw new ConflictException("ILLEGAL_STATE_TRANSITION",
                    "Cannot move an enrollment request from " + this + " to " + target
                            + ". Allowed from " + this + ": "
                            + (allowedNext().isEmpty() ? "nothing, it is final" : allowedNext()));
        }
    }
}
