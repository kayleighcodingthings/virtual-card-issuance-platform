package com.nium.cardplatform.card;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted repository test for the card expiry query.
 * <p>Why a dedicated test for this query?
 * The WHERE status = 'ACTIVE' filter is the critical correctness requirement —
 * it prevents the scheduler from re-processing already-expired or closed cards
 * on every run. Without it, the scheduler is a production-breaking bug.
 * <p>Running against real PostgreSQL (via BaseIntegrationTest + Testcontainers)
 * because the query uses a custom PostgreSQL enum type (card_status) that H2
 * does not support. Only a real PostgreSQL instance gives us a faithful test.
 */
@DisplayName("CardRepository — expiry query")
@Transactional
class CardRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CardRepository cardRepository;

    @BeforeEach
    void clean() {
        cardRepository.deleteAll();
    }

    // Card Helper

    private Card save(CardStatus status, LocalDateTime expiresAt) {
        return cardRepository.save(Card.builder()
                .cardholderName("Test User")
                .balance(BigDecimal.ZERO)
                .status(status)
                .expiresAt(expiresAt)
                .build());
    }

    // Tests

    @Test
    @DisplayName("returns ACTIVE cards past expiry — ignores cards with other statuses")
    void returnsOnlyActiveExpiredCards() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        LocalDateTime future = LocalDateTime.now().plusYears(1);

        Card shouldExpire = save(CardStatus.ACTIVE, past);   // only this one
        Card alreadyExpired = save(CardStatus.EXPIRED, past);   // already expired — skip
        Card blocked = save(CardStatus.BLOCKED, past);   // blocked — skip
        Card closed = save(CardStatus.CLOSED, past);   // closed — skip
        Card notYetExpired = save(CardStatus.ACTIVE, future); // future expiry — skip

        List<Card> result = cardRepository.findExpiredActiveCards(
                LocalDateTime.now(), CardStatus.ACTIVE);

        assertThat(result)
                .as("Only ACTIVE cards past expiry should be returned")
                .containsExactly(shouldExpire);
    }

    @Test
    @DisplayName("returns empty list when no ACTIVE cards have passed expiry")
    void returnsEmptyWhenNothingToExpire() {
        LocalDateTime future = LocalDateTime.now().plusYears(1);

        save(CardStatus.ACTIVE, future);  // active but not expired yet
        save(CardStatus.ACTIVE, future);

        List<Card> result = cardRepository.findExpiredActiveCards(
                LocalDateTime.now(), CardStatus.ACTIVE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns multiple expired active cards")
    void returnsMultipleExpiredActiveCards() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);

        Card card1 = save(CardStatus.ACTIVE, past);
        Card card2 = save(CardStatus.ACTIVE, past);
        Card card3 = save(CardStatus.ACTIVE, past);

        save(CardStatus.EXPIRED, past); // expired — should be skipped

        List<Card> result = cardRepository.findExpiredActiveCards(
                LocalDateTime.now(), CardStatus.ACTIVE);

        assertThat(result)
                .as("All ACTIVE cards past expiry should be returned")
                .hasSize(3)
                .containsExactlyInAnyOrder(card1, card2, card3);
    }

    @Test
    @DisplayName("query is idempotent: once a card is expired, it should no longer be returned by the query")
    void queryIsIdempotentAfterExpiry() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);

        Card card = save(CardStatus.ACTIVE, past);

        // Simulate first run where card gets expired
        card.setStatus(CardStatus.EXPIRED);
        cardRepository.save(card);

        // second run should return nothing
        List<Card> result = cardRepository.findExpiredActiveCards(
                LocalDateTime.now(), CardStatus.ACTIVE);

        assertThat(result)
                .as("After the card is expired, it should no longer be returned by the query")
                .isEmpty();
    }

    @Test
    @DisplayName("boundary condition: cards expiring exactly at the boundary time should be included")
    void cardExpiringExactlyNowIsIncluded() {
        // expiresAt <= :now - boundary is inclusive
        LocalDateTime boundary = LocalDateTime.now();
        save(CardStatus.ACTIVE, boundary.minusSeconds(1)); // expired

        List<Card> result = cardRepository.findExpiredActiveCards(
                boundary, CardStatus.ACTIVE);

        assertThat(result)
                .as("Cards expiring at or before the boundary should be included")
                .hasSize(1);
    }
}
