package com.nium.cardplatform.transaction;

import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import com.nium.cardplatform.transaction.entity.TransactionType;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import com.nium.cardplatform.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.nium.cardplatform.shared.TestFactory.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionServiceTest")
class TransactionServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    @Mock
    CardService cardService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Spy
    SimpleMeterRegistry meterRegistry;

    @InjectMocks
    TransactionService sut;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "maxRetries", 3);
        sut.initMetrics();
    }

    // Debit

    @Nested
    @DisplayName("debit")
    class Debit {

        @Test
        @DisplayName("returns existing transaction for same idempotency key")
        void idempotencyReplay() {
            Card card = activeCard(new BigDecimal("100.00"));
            Transaction existing = successfulDebit(card.getId(), new BigDecimal("10.00"));

            when(transactionRepository.findByItempotencyKey("key-1"))
                    .thenReturn(Optional.of(existing));

            Transaction result = sut.debit(card.getId(), new BigDecimal("10.00"), "key-1");

            assertThat(result).isSameAs(existing);
            verify(cardService, never()).findOrThrow(any());
        }

        @Test
        @DisplayName("processes pending transaction to successful")
        void pendingThenSuccessful() {
            Card card = activeCard(new BigDecimal("200.00"));
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            // First save it as PENDING, then save for update
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            Transaction result = sut.debit(card.getId(), new BigDecimal("50.00"), "happy-key");

            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);

            //save called at least twice, 1 for pending 1 for update
            verify(transactionRepository, atLeast(2)).save(any());
        }

        @Test
        @DisplayName("processes pending transaction to declined when insufficient funds")
        void pendingThenDeclined() {
            Card card = activeCard(new BigDecimal("5.00"));
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "decline-key"))
                    .isInstanceOf(CardPlatformException.class);

            var captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository, atLeast(2)).save(captor.capture());
            Transaction last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(last.getDeclineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("throws CardPlatformException when card is BLOCKED")
        void blockedCard() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "blocked-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException when card is CLOSED")
        void closedCard() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            assertThatThrownBy(() -> sut.debit(card.getId(), new BigDecimal("10.00"), "blocked-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException when card not found")
        void cardNotFound() {
            UUID missing = UUID.randomUUID();
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(missing)).thenThrow(CardPlatformException.notFound(missing));

            assertThatThrownBy(() -> sut.debit(missing, new BigDecimal("10.00"), "missing-key"))
                    .isInstanceOf(CardPlatformException.class);
        }
    }

    // Parameterized Test for balance boundary cases

    @Nested
    @DisplayName("balanceBoundary cases")
    class BalanceBoundary {

        /**
         * Covers the InsufficientFundsException guard in Card.debit().
         * CSV: balance, debitAmount, shouldSucceed
         */
        @ParameterizedTest(name = "balance={0} debit={1} shouldSucceed={2}")
        @CsvSource({
                "100.00, 100.00, true",   // exact balance — succeeds, final balance = 0
                "100.00, 100.01, false",  // one cent over — declines
                "0.00,   0.01,  false",   // zero balance — any debit declines
                "50.00,  25.00, true",    // normal debit — succeeds
                "0.01,   0.01,  true",    // minimum unit debit — succeeds
        })
        @DisplayName("boundary debit: balance={0} amount={1} expected={2}")
        void balanceBoundary(BigDecimal balance, BigDecimal debitAmount, boolean shouldSucceed) {
            Card card = activeCard(balance);
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            if (shouldSucceed) {
                assertThatNoException().isThrownBy(() ->
                        sut.debit(card.getId(), debitAmount, UUID.randomUUID().toString()));
            } else {
                assertThatThrownBy(() -> sut.debit(card.getId(), debitAmount, "boundary-key"))
                        .isInstanceOf(CardPlatformException.class);
            }
        }
    }

    // Transaction Entity Lifecycle

    @Nested
    @DisplayName("transaction state lifecycle")
    class TransactionLifecycle {

        @Test
        @DisplayName("PENDING → SUCCESSFUL via acceptTransaction()")
        void pendingToSuccessful() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-1");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
            txn.acceptTransaction();
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
        }

        @Test
        @DisplayName("PENDING → DECLINED via declineTransaction(reason)")
        void pendingToDeclined() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-2");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.PENDING);
            txn.declineTransaction("INSUFFICIENT_FUNDS");
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.DECLINED);
            assertThat(txn.getDeclineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("acceptTransaction() on non-PENDING transaction throws IllegalStateException")
        void acceptNonPendingThrows() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-3");
            txn.acceptTransaction();
            assertThatThrownBy(txn::acceptTransaction).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("declineTransaction() on non-PENDING transaction throws IllegalStateException")
        void declineNonPendingThrows() {
            Transaction txn = Transaction.pending(UUID.randomUUID(), TransactionType.DEBIT, new BigDecimal("10.00"), "key-4");
            txn.acceptTransaction();
            assertThatThrownBy(() -> txn.declineTransaction("reason")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("credit")
    class Credit {

        @Test
        @DisplayName("returns existing transaction for same idempotency key")
        void idempotencyReplay() {
            Card card = activeCard(BigDecimal.ZERO);
            Transaction existing = successfulCredit(card.getId(), new BigDecimal("50.00"));
            when(transactionRepository.findByItempotencyKey("key-1")).thenReturn(Optional.of(existing));

            Transaction result = sut.credit(card.getId(), new BigDecimal("50.00"), "key-1");

            assertThat(result).isSameAs(existing);
            verify(cardService, never()).findOrThrow(any());
        }

        @Test
        @DisplayName("processes pending credit transaction to successful")
        void pendingThenSuccessful() {
            Card card = activeCard();
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);
            when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            Transaction result = sut.credit(card.getId(), new BigDecimal("25.00"), "credit-key");
            assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESSFUL);
            verify(transactionRepository, atLeast(2)).save(any());
        }

        @Test
        @DisplayName("throws CardPlatformException on CLOSED card")
        void closedCard() {
            Card card = cardWithStatus(CardStatus.CLOSED);
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);

            assertThatThrownBy(() -> sut.credit(card.getId(), new BigDecimal("25.00"), "closed-credit-key"))
                    .isInstanceOf(CardPlatformException.class);
        }

        @Test
        @DisplayName("throws CardPlatformException on BLOCKED card")
        void blockedCard() {
            Card card = cardWithStatus(CardStatus.BLOCKED);
            when(transactionRepository.findByItempotencyKey(any())).thenReturn(Optional.empty());
            when(cardService.findOrThrow(card.getId())).thenReturn(card);

            assertThatThrownBy(() -> sut.credit(card.getId(), new BigDecimal("25.00"), "closed-credit-key"))
                    .isInstanceOf(CardPlatformException.class);
        }
    }
}
