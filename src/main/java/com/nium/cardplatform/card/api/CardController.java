package com.nium.cardplatform.card.api;

import com.nium.cardplatform.card.api.dto.CardDtos;
import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.service.CardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {
    private final CardService cardService;

    @PostMapping
    public ResponseEntity<CardDtos.CardResponse> createCard(@Valid @RequestBody CardDtos.CreateCardRequest request) {
        Card card = cardService.createCard(request.cardholderName(), request.initialBalance(), request.expiresAt());
        MDC.put("cardId", card.getId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(card));
    }

    @GetMapping("/{cardId}")
    public ResponseEntity<CardDtos.CardResponse> getCard(@PathVariable UUID cardId) {
        MDC.put("cardId", cardId.toString());
        return ResponseEntity.ok(toResponse(cardService.getCard(cardId)));
    }

    @PostMapping("/{cardId}/block")
    public ResponseEntity<CardDtos.CardResponse> blockCard(@PathVariable UUID cardId) {
        MDC.put("cardId", cardId.toString());
        return ResponseEntity.ok(toResponse(cardService.blockCard(cardId)));
    }

    @PostMapping("/{cardId}/unblock")
    public ResponseEntity<CardDtos.CardResponse> unblockCard(@PathVariable UUID cardId) {
        MDC.put("cardId", cardId.toString());
        return ResponseEntity.ok(toResponse(cardService.unblockCard(cardId)));
    }

    @PostMapping("/{cardId}/close")
    public ResponseEntity<CardDtos.CardResponse> closeCard(@PathVariable UUID cardId) {
        MDC.put("cardId", cardId.toString());
        return ResponseEntity.ok(toResponse(cardService.closeCard(cardId)));
    }

    private CardDtos.CardResponse toResponse(Card c) {
        return CardDtos.CardResponse.builder()
                .id(c.getId())
                .cardholderName(c.getCardholderName())
                .balance(c.getBalance().stripTrailingZeros())
                .status(c.getStatus().name())
                .expiresAt(c.getExpiresAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
