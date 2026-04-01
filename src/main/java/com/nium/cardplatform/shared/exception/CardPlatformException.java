package com.nium.cardplatform.shared.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class CardPlatformException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private CardPlatformException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }


    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // Factory Methods

    public static CardPlatformException notFound(UUID cardId) {
        return new CardPlatformException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "Card [" + cardId + "] not found.");
    }

    public static CardPlatformException cardNotActive(UUID cardId, String currentStatus) {
        return new CardPlatformException(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_NOT_ACTIVE", String.format("Card %s is not active (status: %s)", cardId, currentStatus));
    }

    public static CardPlatformException cardTerminated(UUID cardId, String status) {
        return new CardPlatformException(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_TERMINATED", String.format("Card %s is terminated (status: %s)", cardId, status));
    }

    public static CardPlatformException invalidTransition(String from, String to) {
        return new CardPlatformException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE_TRANSITION", String.format("Invalid card status transition: %s -> %s", from, to));
    }

    public static CardPlatformException insufficientFunds(UUID cardId) {
        return new CardPlatformException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds on card: " + cardId);
    }

    public static CardPlatformException internalError(String detail) {
        return new CardPlatformException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", detail);
    }


}
