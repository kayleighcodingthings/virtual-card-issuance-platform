package com.nium.cardplatform.card.entity;

public enum CardStatus {
    ACTIVE,
    BLOCKED,
    CLOSED,
    EXPIRED;

    public boolean isTerminated() {
        return this == CLOSED || this == EXPIRED;
    }
}
