package com.nium.cardplatform.transaction.api;

import com.nium.cardplatform.transaction.api.dto.TransactionDtos;
import com.nium.cardplatform.transaction.entity.Transaction;
import com.nium.cardplatform.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.RoundingMode;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class TransactionController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransactionService transactionService;

    @PostMapping("{cardId}/debit")
    public ResponseEntity<TransactionDtos.TransactionResponse> debit(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable UUID cardId,
            @Valid @RequestBody TransactionDtos.DebitRequest request
    ) {
        MDC.put("cardId", cardId.toString());
        Transaction txn = transactionService.debit(cardId, request.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(txn));
    }

    @PostMapping("{cardId}/credit")
    public ResponseEntity<TransactionDtos.TransactionResponse> credit(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable UUID cardId,
            @Valid @RequestBody TransactionDtos.CreditRequest request
    ) {
        MDC.put("cardId", cardId.toString());
        Transaction txn = transactionService.credit(cardId, request.amount(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(txn));
    }

    @GetMapping("/{cardId}/history")
    public ResponseEntity<TransactionDtos.PagedTransactionResponse> getTransactions(
            @PathVariable UUID cardId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        MDC.put("cardId", cardId.toString());
        if (size > 100) size = 100;
        Page<Transaction> txnPage = transactionService.getTransactions(cardId, PageRequest.of(page, size));
        return ResponseEntity.ok(TransactionDtos.PagedTransactionResponse.builder()
                .content(txnPage.getContent().stream().map(this::toResponse).toList())
                .page(txnPage.getNumber())
                .size(txnPage.getSize())
                .totalElements(txnPage.getTotalElements())
                .totalPages(txnPage.getTotalPages())
                .last(txnPage.isLast())
                .build());
    }

    private TransactionDtos.TransactionResponse toResponse(Transaction txn) {
        return TransactionDtos.TransactionResponse.builder()
                .id(txn.getId())
                .cardId(txn.getCardId())
                .type(txn.getType().name())
                .amount(txn.getAmount().setScale(2, RoundingMode.HALF_UP))
                .status(txn.getStatus().name())
                .declineReason(txn.getDeclineReason())
                .idempotencyKey(txn.getIdempotencyKey())
                .createdAt(txn.getCreatedAt())
                .build();
    }
}
