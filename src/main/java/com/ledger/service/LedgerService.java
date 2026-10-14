package com.ledger.service;

import com.ledger.api.dto.TransferRequest;
import com.ledger.api.dto.TransferResponse;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public boolean isIdempotent(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be null or blank");
        }
        return transactionRepository.existsByIdempotencyKey(idempotencyKey);
    }
}
