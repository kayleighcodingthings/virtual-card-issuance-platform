package com.nium.cardplatform.transaction;

import com.nium.cardplatform.shared.exception.GlobalExceptionHandler;
import com.nium.cardplatform.transaction.api.TransactionController;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.nium.cardplatform.shared.TestFactory.successfulCredit;
import static com.nium.cardplatform.shared.TestFactory.successfulDebit;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for the transaction endpoints.
 * <p>Focus: Idempotency-Key header enforcement and amount validation.
 * The Idempotency-Key header is mandatory on debit and credit — missing it
 * must return 400 immediately, before any business logic runs.
 */
@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("TransactionController — input validation")
class TransactionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionService transactionService;

    private static final UUID CARD_ID = UUID.randomUUID();

    // --- POST /debit ---

    @Nested
    @DisplayName("POST /api/v1/cards/{id}/debit")
    class Debit {

        @Test
        @DisplayName("valid request returns 201")
        void validRequest_returns201() throws Exception {
            when(transactionService.debit(any(), any(), any())).thenReturn(successfulDebit(CARD_ID, new BigDecimal("50.00")));

            mockMvc.perform(post("/api/v1/cards/{id}/debit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": 50.00
                                    }
                                    """
                            ))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
        }

        @Test
        @DisplayName("400 when Idempotency-Key header is missing")
        void missingIdempotencyKey_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/debit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "amount": 50.00
                                    }
                                    """
                            ))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when amount is zero")
        void zeroAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/debit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": 0.00
                                    }
                                    """
                            ))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.amount").exists());
        }

        @Test
        @DisplayName("400 when amount is negative")
        void negativeAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/debit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": -10.00
                                    }
                                    """
                            ))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.amount").exists());
        }

        @Test
        @DisplayName("400 when amount is missing")
        void missingAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/debit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // --- POST /credit ---

    @Nested
    @DisplayName("POST /api/v1/cards/{id}/credit")
    class Credit {
        @Test
        @DisplayName("201 when request is valid")
        void validRequest_returns201() throws Exception {
            when(transactionService.credit(any(), any(), any())).thenReturn(successfulCredit(CARD_ID, new BigDecimal("100.00")));

            mockMvc.perform(post("/api/v1/cards/{id}/credit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": 100.0
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                    .andExpect(jsonPath("$.type").value("CREDIT"));
        }

        @Test
        @DisplayName("400 when Idempotency-Key header is missing")
        void missingIdempotencyKey_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/credit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "amount": 100.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when amount is zero")
        void zeroAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/credit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": 0.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.amount").exists());
        }

        @Test
        @DisplayName("400 when amount is negative")
        void negativeAmount_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards/{id}/credit", CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content("""
                                    {
                                        "amount": -5.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest());
        }

    }

    // --- GET /history ---

    @Nested
    @DisplayName("GET /api/v1/cards/{id}/history")
    class TransactionHistory {

        @Test
        @DisplayName("200 with paginated transaction history")
        void validRequest_returns200() throws Exception {
            Page<Transaction> page = new PageImpl<>(
                    List.of(successfulDebit(CARD_ID, new BigDecimal("50.00"))),
                    PageRequest.of(0, 20),
                    1
            );

            when(transactionService.getTransactions(eq(CARD_ID), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/v1/cards/{id}/history", CARD_ID)
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].status").value("SUCCESSFUL"))
                    .andExpect(jsonPath("$.content[0].type").value("DEBIT"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.last").value(true));
        }

        @Test
        @DisplayName("400 when cardId is not a valid UUID")
        void invalidUuid_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/cards/not-a-uuid/history"))
                    .andExpect(status().isBadRequest());
        }
    }

}
