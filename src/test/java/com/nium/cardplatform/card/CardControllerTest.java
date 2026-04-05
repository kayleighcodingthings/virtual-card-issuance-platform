package com.nium.cardplatform.card;

import com.nium.cardplatform.card.api.CardController;
import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.entity.CardStatus;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import com.nium.cardplatform.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests using @WebMvcTest.
 * <p>Scope: input validation rules (@Valid, @NotBlank, @DecimalMin) and
 * missing/malformed request headers. These are fast, isolated tests
 * that run without a database or Kafka.
 * <p>What these do NOT test: business logic, database behaviour, or
 * idempotency — those are covered by CardApiIntegrationTest which
 * runs the full stack against real PostgreSQL.
 * <p>Why @Import(GlobalExceptionHandler.class)?
 * {@link WebMvcTest} loads only the web layer. Without explicitly importing
 * the exception handler, Spring uses its default error handling and
 * our ProblemDetail responses won't be present in the test.
 */
@WebMvcTest(CardController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("CardController - input validation")
class CardControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    CardService cardService;

    // --- POST /api/v1/cards ---

    @Nested
    @DisplayName("POST /api/v1/cards")
    class CreateCard {

        @Test
        @DisplayName("valid request returns 201 with card details")
        void validRequest_returns201() throws Exception {
            Card mockCard = mockCard(CardStatus.ACTIVE);
            when(cardService.createCard(any(), any(), any())).thenReturn(mockCard);

            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "cardholderName": "Alice Smith",
                                      "initialBalance": 100.00
                                    }
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.cardholderName").value("Alice Smith"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("blank cardholderName returns 400 with validation error")
        void blankName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "cardholderName": "",
                                      "initialBalance": 100.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.cardholderName").exists());
        }

        @Test
        @DisplayName("missing cardholderName returns 400 with validation error")
        void missingName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "initialBalance": 100.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("negative initialBalance returns 400 with validation error")
        void negativeBalance_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "cardholderName": "Alice Smith",
                                      "initialBalance": -50.00
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors.initialBalance").exists());
        }

        @Test
        @DisplayName("missing initialBalance returns 400 with validation error")
        void missingBalance_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "cardholderName": "Alice Smith"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("empty body returns 400 with validation error")
        void emptyBody_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }


    // --- GET /api/v1/cards/{id} ---

    @Nested
    @DisplayName("GET /api/v1/cards/{id}")
    class GetCard {

        @Test
        @DisplayName("400 when cardId is not a valid UUID")
        void invalidUuid_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/cards/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 when cardId doesn't exist")
        void nonexistentUuid_returns404() throws Exception {
            UUID randomId = UUID.randomUUID();
            when(cardService.getCard(randomId))
                    .thenThrow(CardPlatformException.notFound(randomId));

            mockMvc.perform(get("/api/v1/cards/{id}", randomId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/cards/{id}/block and /unblock and /close")
    class StatusTransitions {

        // --- PATCH /api/v1/cards/{id} with status = BLOCKED ---

        @Test
        @DisplayName("block - valid cardId returns 200 with updated card status")
        void block_validId_returns200() throws Exception {
            Card mockCard = mockCard(CardStatus.BLOCKED);
            when(cardService.blockCard(any())).thenReturn(mockCard);

            mockMvc.perform(patch("/api/v1/cards/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "BLOCKED"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("BLOCKED"));
        }

        // --- PATCH /api/v1/cards/{id} with status = ACTIVE ---

        @Test
        @DisplayName("unblock - valid cardId returns 200 with updated card status")
        void unblock_validId_returns200() throws Exception {
            Card mockCard = mockCard(CardStatus.ACTIVE);
            when(cardService.unblockCard(any())).thenReturn(mockCard);

            mockMvc.perform(patch("/api/v1/cards/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "ACTIVE"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        // --- PATCH /api/v1/cards/{id} with invalid status ---

        @Test
        @DisplayName("PATCH with invalid status returns 422")
        void invalidStatus_returns422() throws Exception {
            mockMvc.perform(patch("/api/v1/cards/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "CLOSED"}
                                    """))
                    .andExpect(status().isUnprocessableEntity());
        }

        // --- PATCH /api/v1/cards/{id} with missing status ---

        @Test
        @DisplayName("PATCH with missing status returns 400")
        void missingStatus_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/cards/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/cards/{id} — close card")
    class CloseCard {

        // --- DELETE /api/v1/cards/{id} ---

        @Test
        @DisplayName("DELETE returns 200 with CLOSED card")
        void close_returns200() throws Exception {
            Card mockCard = mockCard(CardStatus.CLOSED);
            when(cardService.closeCard(any())).thenReturn(mockCard);

            mockMvc.perform(delete("/api/v1/cards/{id}", UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }
    }

    // --- Error Handling ---

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("malformed JSON body returns 400 MALFORMED_REQUEST_BODY")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ invalid json }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
        }

        @Test
        @DisplayName("missing request body returns 400 MALFORMED_REQUEST_BODY")
        void missingBody_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
        }

        @Test
        @DisplayName("invalid enum value in PATCH body returns 400 MALFORMED_REQUEST_BODY")
        void invalidEnumValue_returns400() throws Exception {
            mockMvc.perform(patch("/api/v1/cards/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "BANANA"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
        }

        @Test
        @DisplayName("GET on POST-only endpoint returns 405 METHOD_NOT_ALLOWED")
        void wrongMethod_returns405() throws Exception {
            mockMvc.perform(get("/api/v1/cards")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
        }
    }

    private Card mockCard(CardStatus status) {
        return Card.builder()
                .id(UUID.randomUUID())
                .cardholderName("Alice Smith")
                .balance(BigDecimal.valueOf(1000))
                .status(status)
                .expiresAt(LocalDateTime.now().plusYears(1))
                .version(0L)
                .build();
    }
}
