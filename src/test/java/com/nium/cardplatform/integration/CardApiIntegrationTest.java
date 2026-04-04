package com.nium.cardplatform.integration;

import com.nium.cardplatform.card.api.dto.CardDtos;
import com.nium.cardplatform.card.repository.CardRepository;
import com.nium.cardplatform.transaction.api.dto.TransactionDtos;
import com.nium.cardplatform.transaction.entity.TransactionStatus;
import com.nium.cardplatform.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Card API Integration Tests")
class CardApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @BeforeEach
    void clean() {
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
    }

    // --- Create Card ---

    @Test
    @DisplayName("Create card with valid data returns 201")
    void createCard_returns201() {
        var resp = createCard("Alice Smith", "250.00");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().status()).isEqualTo("ACTIVE");
        assertThat(resp.getBody().id()).isNotNull();
    }

    @Test
    @DisplayName("Create card with blank name returns 400")
    void createCard_blankName_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var resp = restTemplate.exchange(
                "/api/v1/cards",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "cardholderName", "",
                        "initialBalance", "100"
                ), headers),
                String.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Debit ---

    @Test
    @DisplayName("Debit with valid data returns 201 and deducts from balance")
    void debit_returns201() {
        UUID cardId = createCard("Bob Smith", "500.00").getBody().id();
        var response = debit(cardId, "100.00", UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("SUCCESSFUL");

        var card = restTemplate.getForEntity("/api/v1/cards/" + cardId, CardDtos.CardResponse.class);
        assertThat(card.getBody().balance()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Debit with insufficient funds returns 422")
    void debit_insufficientFunds_returns422() {
        UUID cardId = createCard("Charlie Brown", "50.00").getBody().id();
        var response = debitForError(cardId, "100.00", UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("Debit on blocked card returns 422")
    void debit_blockedCard_returns422() {
        UUID cardId = createCard("David Lee", "300.00").getBody().id();
        restTemplate.postForEntity("/api/v1/cards/" + cardId + "/block", null, Void.class);
        var response = debitForError(cardId, "50.00", UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("Debit on unknown card returns 404")
    void debit_unknownCard_returns404() {
        var response = debitForError(UUID.randomUUID(), "50.00", UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Debit missing Idempotency-Key header returns 400")
    void debit_missingIdempotencyHeader_returns400() {
        UUID cardId = createCard("Eve Adams", "400.00").getBody().id();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange(
                "/api/v1/cards/" + cardId + "/debit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "50.00"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Credit ---

    @Test
    @DisplayName("Credit with valid data returns 201 and credit balance")
    void credit_returns201() {
        UUID cardId = createCard("Frank Miller", "100.00").getBody().id();
        var response = credit(cardId, "50.00", UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("SUCCESSFUL");

        var card = restTemplate.getForEntity("/api/v1/cards/" + cardId, CardDtos.CardResponse.class);
        assertThat(card.getBody().balance()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Credit on closed card returns 422")
    void credit_closedCard_returns422() {
        UUID cardId = createCard("George Paul", "300.00").getBody().id();
        restTemplate.postForEntity("/api/v1/cards/" + cardId + "/close", null, Void.class);
        var response = creditForError(cardId, "50.00", UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- Idempotency ---

    @Test
    @DisplayName("Duplicate debit with same Idempotency-Key does not double debit")
    void idempotency_noDoubleDebit() throws InterruptedException {
        UUID cardId = createCard("Henry Ford", "500.00").getBody().id();
        String key = UUID.randomUUID().toString();

        debit(cardId, "100.00", key);
        Thread.sleep(100); // Ensure the first request is processed before sending the duplicate
        debit(cardId, "100.00", key);

        var card = restTemplate.getForEntity("/api/v1/cards/" + cardId, CardDtos.CardResponse.class);
        assertThat(card.getBody().balance()).isEqualByComparingTo("400.00");

        long count = transactionRepository.countByCardIdAndStatus(cardId, TransactionStatus.SUCCESSFUL);
        assertThat(count).isEqualTo(1);
    }

    // --- Transaction History ---

    @Test
    @DisplayName("GET /history returns paginated transaction history")
    void transactionHistory() {
        UUID cardId = createCard("Iris West", "1000.00").getBody().id();
        debit(cardId, "100.00", UUID.randomUUID().toString());
        credit(cardId, "50.00", UUID.randomUUID().toString());
        debit(cardId, "25.00", UUID.randomUUID().toString());

        var response = restTemplate.getForEntity(
                "/api/v1/cards/" + cardId + "/history?page=0&size=10",
                TransactionDtos.PagedTransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().totalElements()).isEqualTo(3);
    }

    // --- Helpers ---

    private ResponseEntity<CardDtos.CardResponse> createCard(String name, String balance) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/cards",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "cardholderName", name,
                        "initialBalance", balance
                ), headers),
                CardDtos.CardResponse.class
        );
    }

    private ResponseEntity<TransactionDtos.TransactionResponse> debit(
            UUID cardId, String amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/cards/" + cardId + "/debit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount), headers),
                TransactionDtos.TransactionResponse.class
        );
    }

    private ResponseEntity<TransactionDtos.TransactionResponse> credit(
            UUID cardId, String amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/cards/" + cardId + "/credit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount), headers),
                TransactionDtos.TransactionResponse.class
        );
    }

    private ResponseEntity<String> debitForError(
            UUID cardId, String amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/cards/" + cardId + "/debit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount), headers),
                String.class
        );
    }

    private ResponseEntity<String> creditForError(
            UUID cardId, String amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/cards/" + cardId + "/credit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount), headers),
                String.class
        );
    }

}
