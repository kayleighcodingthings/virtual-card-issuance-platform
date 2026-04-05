package com.nium.cardplatform.card.api;

import com.nium.cardplatform.card.api.dto.CardDtos;
import com.nium.cardplatform.card.entity.Card;
import com.nium.cardplatform.card.service.CardService;
import com.nium.cardplatform.shared.exception.CardPlatformException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.RoundingMode;
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

    /**
     * Partial update of a card resource. Currently supports status transitions:
     * <ul>
     *   <li>{@code {"status": "BLOCKED"}} — blocks an ACTIVE card</li>
     *   <li>{@code {"status": "ACTIVE"}} — unblocks a BLOCKED card</li>
     * </ul>
     * Idempotent: patching to the current status returns 200 with no state change.
     *
     * @param cardId  the card to update
     * @param request the fields to patch
     * @return the updated card
     */
    @PatchMapping("/{cardId}")
    public ResponseEntity<CardDtos.CardResponse> updateCard(
            @PathVariable UUID cardId,
            @Valid @RequestBody CardDtos.PatchCardRequest request) {
        MDC.put("cardId", cardId.toString());

        Card result = switch (request.status()) {
            case BLOCKED -> cardService.blockCard(cardId);
            case ACTIVE -> cardService.unblockCard(cardId);
            default -> throw CardPlatformException.invalidTransition(
                    "current", request.status().name());
        };

        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * Closes (terminates) a card. Modelled as DELETE because the card is being
     * permanently retired — no further transactions or status transitions are
     * permitted after closure.
     * <p>Idempotent: deleting an already-closed card returns 200.
     *
     * @param cardId the card to close
     * @return the closed card
     */
    @DeleteMapping("/{cardId}")
    public ResponseEntity<CardDtos.CardResponse> closeCard(@PathVariable UUID cardId) {
        MDC.put("cardId", cardId.toString());
        return ResponseEntity.ok(toResponse(cardService.closeCard(cardId)));
    }

    private CardDtos.CardResponse toResponse(Card c) {
        return CardDtos.CardResponse.builder()
                .id(c.getId())
                .cardholderName(c.getCardholderName())
                .balance(c.getBalance().setScale(2, RoundingMode.HALF_UP))
                .status(c.getStatus().name())
                .expiresAt(c.getExpiresAt())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
