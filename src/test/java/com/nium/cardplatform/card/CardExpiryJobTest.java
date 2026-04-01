package com.nium.cardplatform.card;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.scheduler.CardExpiryJob;
import com.nium.cardplatform.card.service.CardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.nium.cardplatform.shared.TestFactory.expiredCard;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CardExpiryJobTest")
class CardExpiryJobTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    CardService cardService;

    @InjectMocks
    CardExpiryJob expiryJob;

    @Test
    @DisplayName("expires only ACTIVE cards past expiresAt — not already expired/closed")
    void expiresOnlyActiveCards() {
        List<Card> cards = List.of(expiredCard(), expiredCard());
        when(cardRepository.findExpiredActiveCards(any(), eq(CardStatus.ACTIVE)))
                .thenReturn(cards);

        expiryJob.execute(null);

        verify(cardService, times(2)).expireCard(any());
    }

    @Test
    @DisplayName("does nothing when no cards need expiring")
    void noCardsToExpire() {
        when(cardRepository.findExpiredActiveCards(any(), eq(CardStatus.ACTIVE)))
                .thenReturn(List.of());

        expiryJob.execute(null);

        verify(cardService, never()).expireCard(any());
    }

    @Test
    @DisplayName("continues expiring other cards even if one fails")
    void continuesOnFailure() {
        Card card1 = expiredCard();
        Card card2 = expiredCard();

        when(cardRepository.findExpiredActiveCards(any(), eq(CardStatus.ACTIVE)))
                .thenReturn(List.of(card1, card2));
        doThrow(new RuntimeException("DB error")).when(cardService).expireCard(card1);

        expiryJob.execute(null);

        // card2 must still be expired, despite card1 failing
        verify(cardService).expireCard(card2);
    }

    @Test
    @DisplayName("queries only ACTIVE cards to be idempotent and avoid re-processing already expired/closed cards")
    void queryFilterActiveStatus() {
        when(cardRepository.findExpiredActiveCards(any(), eq(CardStatus.ACTIVE)))
                .thenReturn(List.of());
        expiryJob.execute(null);

        // The query must filter by ACTIVE status to be idempotent and avoid re-processing already expired/closed cards
        verify(cardRepository).findExpiredActiveCards(any(), eq(CardStatus.ACTIVE));
    }
}
