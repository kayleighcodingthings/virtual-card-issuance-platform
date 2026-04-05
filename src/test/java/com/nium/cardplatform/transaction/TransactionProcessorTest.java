package com.nium.cardplatform.shared;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.events.CardAuditEvent;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import com.nium.cardplatform.transaction.service.TransactionProcessor;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nium.cardplatform.shared.TestFactory.activeCard;
import static com.nium.cardplatform.shared.TestFactory.cardWithStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TransactionProcessor} — the transactional unit of work
 * that owns the PENDING → SUCCESSFUL | DECLINED lifecycle.
 * <p>These tests verify behaviour that {@code TransactionServiceTest} cannot cover
 * because it mocks {@code TransactionProcessor} entirely: PENDING record creation
 * before balance mutation, correct transition to SUCCESSFUL/DECLINED, audit event
 * publishing, metric counter increments, and decline-record persistence for
 * inactive cards.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionProcessorTest")
class TransactionProcessorTest {
    @Mock
    CardService cardService;

    @Mock
    CardRepository cardRepository;

    @Mock
    TransactionRepository transactionRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Spy
    SimpleMeterRegistry meterRegistry;

    @InjectMocks
    TransactionProcessor sut;

    @BeforeEach
    void setUp() {
        sut.initMetrics();
    }

    // --- processDebit ---

    @Nested
    @DisplayName("processDebit")
    class ProcessDebit {

        @Test
        @DisplayName("writes PENDING record before balance mutation, then transitions to SUCCESSFUL")
        void pendingThenSuccessful() {
            Card card = activeCard(new BigDecimal("100.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<TransactionStatus> statusesAtSaveTime = new ArrayList<>();
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
                Transaction t = inv.getArgument(0);
                statusesAtSaveTime.add(t.getStatus());
                return t;
            });

            Transaction result = sut.processDebit(card.getId(), new BigDecimal("50.00"), "debit-key-1");

            assertThat(statusesAtSaveTime).containsExactly(TransactionStatus.PENDING, TransactionStatus.SUCCESSFUL);
            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(result.getDeclineReason()).isNull();
            assertThat(result.getType()).isEqualTo(TransactionType.DEBIT);
            assertThat(result.getAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("deducts amount from card balance")
        void deductsBalance() {
            Card card = activeCard(new BigDecimal("100.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processDebit(card.getId(), new BigDecimal("30.00"), "key-2");

            ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).save(cardCaptor.capture());
            assertThat(cardCaptor.getValue().getBalance()).isEqualByComparingTo("70.00");
        }

        @Test
        @DisplayName("publishes audit event on successful debit")
        void publishesAuditEvent() {
            Card card = activeCard(new BigDecimal("500.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processDebit(card.getId(), new BigDecimal("25.00"), "key-3");

            ArgumentCaptor<CardAuditEvent> eventCaptor = ArgumentCaptor.forClass(CardAuditEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventType()).isEqualTo("TRANSACTION_DEBIT");
            assertThat(eventCaptor.getValue().getCardId()).isEqualTo(card.getId());
            assertThat(eventCaptor.getValue().getAmount()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("increments debit success counter on successful debit")
        void incrementsSuccessCounter() {
            Card card = activeCard(new BigDecimal("100.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processDebit(card.getId(), new BigDecimal("10.00"), "key-4");

            assertThat(meterRegistry.counter("transactions.debit.success").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("transitions PENDING to DECLINED on insufficient funds and throws")
        void insufficientFunds_declines() {
            Card card = activeCard(new BigDecimal("5.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("50.00"), "key-5")
            ).isInstanceOf(CardPlatformException.class);

            // Transaction was saved twice: PENDING, then DECLINED
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository, times(2)).save(txnCaptor.capture());

            Transaction declined = txnCaptor.getAllValues().get(1);
            assertThat(declined.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(declined.getDeclineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("increments declined counter on insufficient funds")
        void insufficientFunds_incrementsDeclinedCounter() {
            Card card = activeCard(new BigDecimal("1.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("10.00"), "key-6")
            ).isInstanceOf(CardPlatformException.class);

            assertThat(meterRegistry.counter("transactions.debit.declined").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not mutate balance on insufficient funds")
        void insufficientFunds_balanceUnchanged() {
            Card card = activeCard(new BigDecimal("5.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("50.00"), "key-7")
            ).isInstanceOf(CardPlatformException.class);

            // Card was never saved — balance mutation rolled back by exception
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not publish audit event on insufficient funds")
        void insufficientFunds_noAuditEvent() {
            Card card = activeCard(new BigDecimal("5.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("50.00"), "key-8")
            ).isInstanceOf(CardPlatformException.class);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("writes DECLINED record and throws when card is BLOCKED")
        void blockedCard_declinesImmediately() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("10.00"), "key-9")
            ).isInstanceOf(CardPlatformException.class);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());

            // Only one save — DECLINED immediately, no PENDING step
            Transaction declined = txnCaptor.getValue();
            assertThat(declined.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(declined.getDeclineReason()).contains("BLOCKED");
        }

        @Test
        @DisplayName("writes DECLINED record and throws when card is CLOSED")
        void closedCard_declinesImmediately() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("10.00"), "key-10")
            ).isInstanceOf(CardPlatformException.class);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.DECLINED);
        }

        @Test
        @DisplayName("writes DECLINED record and throws when card is EXPIRED")
        void expiredCard_declinesImmediately() {
            Card card = cardWithStatus(CardStatus.EXPIRED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processDebit(card.getId(), new BigDecimal("10.00"), "key-11")
            ).isInstanceOf(CardPlatformException.class);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.DECLINED);
        }

        @Test
        @DisplayName("propagates CardPlatformException when card not found")
        void cardNotFound_throws() {
            UUID missing = UUID.randomUUID();
            when(cardService.findOrThrow(missing)).thenThrow(CardPlatformException.notFound(missing));

            assertThatThrownBy(() ->
                    sut.processDebit(missing, new BigDecimal("10.00"), "key-12")
            ).isInstanceOf(CardPlatformException.class);

            verify(transactionRepository, never()).save(any());
        }
    }

    // --- processCredit ---

    @Nested
    @DisplayName("processCredit")
    class ProcessCredit {

        @Test
        @DisplayName("writes PENDING record before balance mutation, then transitions to SUCCESSFUL")
        void pendingThenSuccessful() {
            Card card = activeCard(new BigDecimal("100.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<TransactionStatus> statusesAtSaveTime = new ArrayList<>();
            when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
                Transaction t = inv.getArgument(0);
                statusesAtSaveTime.add(t.getStatus());
                return t;
            });

            Transaction result = sut.processCredit(card.getId(), new BigDecimal("50.00"), "credit-key-1");

            assertThat(statusesAtSaveTime).containsExactly(TransactionStatus.PENDING, TransactionStatus.SUCCESSFUL);
            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(result.getDeclineReason()).isNull();
            assertThat(result.getType()).isEqualTo(TransactionType.CREDIT);
            assertThat(result.getAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("adds amount to card balance")
        void addsBalance() {
            Card card = activeCard(new BigDecimal("100.00"));
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processCredit(card.getId(), new BigDecimal("75.00"), "credit-key-2");

            ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).save(cardCaptor.capture());
            assertThat(cardCaptor.getValue().getBalance()).isEqualByComparingTo("175.00");
        }

        @Test
        @DisplayName("publishes audit event on successful credit")
        void publishesAuditEvent() {
            Card card = activeCard(BigDecimal.ZERO);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processCredit(card.getId(), new BigDecimal("100.00"), "credit-key-3");

            ArgumentCaptor<CardAuditEvent> eventCaptor = ArgumentCaptor.forClass(CardAuditEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventType()).isEqualTo("TRANSACTION_CREDIT");
            assertThat(eventCaptor.getValue().getAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("increments credit success counter")
        void incrementsSuccessCounter() {
            Card card = activeCard(BigDecimal.ZERO);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            sut.processCredit(card.getId(), new BigDecimal("10.00"), "credit-key-4");

            assertThat(meterRegistry.counter("transactions.credit.success").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("writes DECLINED record and throws when card is BLOCKED")
        void blockedCard_declinesImmediately() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processCredit(card.getId(), new BigDecimal("10.00"), "credit-key-5")
            ).isInstanceOf(CardPlatformException.class);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.DECLINED);
        }

        @Test
        @DisplayName("writes DECLINED record and throws when card is CLOSED")
        void closedCard_declinesImmediately() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processCredit(card.getId(), new BigDecimal("10.00"), "credit-key-6")
            ).isInstanceOf(CardPlatformException.class);

            verify(transactionRepository).save(any());
            verify(cardRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not publish audit event when card is not active")
        void inactiveCard_noAuditEvent() {
            Card card = cardWithStatus(CardStatus.EXPIRED);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() ->
                    sut.processCredit(card.getId(), new BigDecimal("10.00"), "credit-key-7")
            ).isInstanceOf(CardPlatformException.class);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("credits from zero balance succeeds")
        void creditFromZero() {
            Card card = activeCard(BigDecimal.ZERO);
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = sut.processCredit(card.getId(), new BigDecimal("999.99"), "credit-key-8");

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            assertThat(card.getBalance()).isEqualByComparingTo("999.99");
        }
    }
}
