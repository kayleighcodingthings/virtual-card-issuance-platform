package com.nium.cardplatform.card.entity;

public enum CardStatus {
    ACTIVE,
    BLOCKED,
    CLOSED,
    EXPIRED;

    /**
     * Returns {@code true} if this status represents a terminal state from which
     * the card can never return to ACTIVE.
     * <p>CLOSED and EXPIRED are both terminal — no further transactions or status
     * transitions (except CLOSED → CLOSED idempotently) are permitted.
     * BLOCKED is intentionally excluded: a blocked card can still be unblocked.
     * @return {@code true} for CLOSED and EXPIRED; {@code false} for ACTIVE and BLOCKED
     */
    public boolean isTerminated() {
        return this == CLOSED || this == EXPIRED;
    }
}
