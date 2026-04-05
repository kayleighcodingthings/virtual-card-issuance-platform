package com.nium.cardplatform.card;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.events.CardAuditEvent;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Optional;
import java.util.UUID;

import static com.nium.cardplatform.shared.TestFactory.activeCard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CardServiceTest")
class CardServiceTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Spy
    SimpleMeterRegistry meterRegistry;

    @InjectMocks
    CardService sut;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "defaultExpiry", Period.ofYears(2));
        sut.initMetrics();
    }

    @Nested
    @DisplayName("createCard")
    class CreateCard {

        @Test
        @DisplayName("creates ACTIVE card")
        void createsCard() {
            Card saved = activeCard(new BigDecimal("100.00"));
            when(cardRepository.save(any())).thenReturn(saved);

            Card result = sut.createCard("Alice Smith", new BigDecimal("100.00"), null);

            assertThat(result.getStatus()).isEqualTo(CardStatus.ACTIVE);
            verify(eventPublisher).publishEvent(any(CardAuditEvent.class));
        }

        @Test
        @DisplayName("uses default expiry when expiresAt is null")
        void defaultExpiry() {
            when(cardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            sut.createCard("Bob Smith", BigDecimal.ZERO, null);

            ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).save(cardCaptor.capture());
            assertThat(cardCaptor.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusYears(1));
        }

        @Test
        @DisplayName("rejects past expiresAt")
        void pastExpiry() {
            assertThatThrownBy(() ->
                    sut.createCard("Charlie Smith", BigDecimal.ZERO, LocalDateTime.now().minusDays(1))
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("blockCard")
    class BlockCard {

        @Test
        @DisplayName("ACTIVE → BLOCKED")
        void blocksActiveCard() {
            Card card = activeCard(BigDecimal.ZERO);
            when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            Card result = sut.updateCardStatus(card.getId(), CardStatus.BLOCKED);

            assertThat(result.getStatus()).isEqualTo(CardStatus.BLOCKED);
            verify(eventPublisher).publishEvent(any(CardAuditEvent.class));
        }

        @Test
        @DisplayName("blocking already-BLOCKED card is idempotent")
        void idempotentBlock() {
            Card card = activeCard(BigDecimal.ZERO);
            card.setStatus(CardStatus.BLOCKED);
            when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

            sut.updateCardStatus(card.getId(), CardStatus.BLOCKED);

            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("cannot block CLOSED card")
        void blockClosed() {
            Card card = activeCard(BigDecimal.ZERO);
            card.setStatus(CardStatus.CLOSED);
            when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

            assertThatThrownBy(() -> sut.updateCardStatus(card.getId(), CardStatus.BLOCKED))
                    .isInstanceOf(CardPlatformException.class);
        }
    }

    @Nested
    @DisplayName("closeCard")
    class CloseCard {

        @Test
        @DisplayName("ACTIVE → CLOSED (terminate)")
        void closesCard() {
            Card card = activeCard(BigDecimal.ZERO);
            when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
            when(cardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            Card result = sut.closeCard(card.getId());

            assertThat(result.getStatus()).isEqualTo(CardStatus.CLOSED);
            verify(eventPublisher).publishEvent(any(CardAuditEvent.class));
        }

        @Test
        @DisplayName("closing already-CLOSED card is idempotent")
        void idempotentClose() {
            Card card = activeCard(BigDecimal.ZERO);
            card.setStatus(CardStatus.CLOSED);
            when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

            sut.closeCard(card.getId());

            verify(cardRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("findOrThrow throws CardPlatformException when card not found")
    void findOrThrowMissing() {
        UUID id = UUID.randomUUID();
        when(cardRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.findOrThrow(id))
                .isInstanceOf(CardPlatformException.class);
    }
}
