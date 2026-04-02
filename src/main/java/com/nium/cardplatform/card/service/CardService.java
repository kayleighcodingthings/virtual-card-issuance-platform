package com.nium.cardplatform.card.service;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.shared.events.CardAuditEvent;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardService {

    private final CardRepository cardRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${app.card.default-expiry:P2Y}")
    private Period defaultExpiry;

    private Counter cardsCreatedCounter;
    private Counter cardsExpiredCounter;

    @PostConstruct
    public void initMetrics() {
        cardsCreatedCounter = meterRegistry.counter("cards.created");
        cardsExpiredCounter = meterRegistry.counter("cards.expired");
    }

    @Timed(value = "card.create.time", description = "Time taken to create a card")
    @Transactional
    public Card createCard(String cardholderName, BigDecimal initialBalance, LocalDateTime expiresAt) {
        LocalDateTime expiry = expiresAt != null ? expiresAt : LocalDateTime.now().plus(defaultExpiry);

        if (expiry.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("expiresAt [" + expiresAt + "] is not in the future.");
        }

        Card card = Card.builder()
                .cardholderName(cardholderName)
                .balance(initialBalance != null ? initialBalance : BigDecimal.ZERO)
                .status(CardStatus.ACTIVE)
                .expiresAt(expiry)
                .build();

        Card saved = cardRepository.save(card);
        cardsCreatedCounter.increment();
        log.info("Card created: cardId={} cardholder={}", saved.getId(), saved.getCardholderName());
        eventPublisher.publishEvent(CardAuditEvent.onCreateCard(saved.getId(), saved.getCardholderName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Card getCard(UUID cardId) {
        return findOrThrow(cardId);
    }

    @Transactional
    public Card blockCard(UUID cardId) {
        Card card = findOrThrow(cardId);
        if (card.isTerminated()) {
            throw CardPlatformException.cardTerminated(cardId, card.getStatus().name());
        }

        if (card.getStatus() == CardStatus.BLOCKED) {
            log.info("Card [{}] was already blocked.", cardId);
            return card; // Idempotent
        }

        card.setStatus(CardStatus.BLOCKED);
        Card saved = cardRepository.save(card);
        log.info("Card [{}] blocked.", cardId);
        eventPublisher.publishEvent(CardAuditEvent.statusChanged(cardId, "ACTIVE", "BLOCKED"));
        return saved;
    }

    @Transactional
    public Card unblockCard(UUID cardId) {
        Card card = findOrThrow(cardId);
        if (card.isTerminated()) {
            throw CardPlatformException.cardTerminated(cardId, card.getStatus().name());
        }

        if (card.getStatus() == CardStatus.ACTIVE) {
            log.info("Card [{}] was already active.", cardId);
            return card; // Idempotent
        }

        if (card.getStatus() != CardStatus.BLOCKED) {
            throw CardPlatformException.invalidTransition(card.getStatus().name(), "ACTIVE");
        }

        card.setStatus(CardStatus.ACTIVE);
        Card saved = cardRepository.save(card);
        log.info("Card [{}] unblocked.", cardId);
        eventPublisher.publishEvent(CardAuditEvent.statusChanged(cardId, "BLOCKED", "ACTIVE"));
        return saved;
    }

    @Transactional
    public Card closeCard(UUID cardId) {
        Card card = findOrThrow(cardId);
        if (card.getStatus() == CardStatus.CLOSED) {
            log.info("Card [{}] was already closed.", cardId);
            return card; // Idempotent
        }

        if (card.getStatus() == CardStatus.EXPIRED) {
            throw CardPlatformException.cardTerminated(cardId, card.getStatus().name());
        } // Handle EXPIRED on its own not through isTerminated() because it is contradictory to the above if (CLOSED)

        String old = card.getStatus().name();
        card.setStatus(CardStatus.CLOSED);
        Card saved = cardRepository.save(card);
        log.info("Card [{}] closed.", cardId);
        eventPublisher.publishEvent(CardAuditEvent.statusChanged(cardId, "ACTIVE", "CLOSED"));
        return saved;
    }

    @Transactional
    public void expireCard(Card card) {
        card.setStatus(CardStatus.EXPIRED);
        cardRepository.save(card);
        cardsExpiredCounter.increment();
        eventPublisher.publishEvent(CardAuditEvent.onExpireCard(card.getId()));
        log.info("Card [{}] expired.", card.getId());
    }

    public Card findOrThrow(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> CardPlatformException.notFound(cardId));
    }
}
