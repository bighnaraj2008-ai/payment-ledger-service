package com.ledger.service;

import com.ledger.api.dto.TransferRequest;
import com.ledger.domain.exception.CurrencyMismatchException;
import com.ledger.domain.model.Account;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public void validateTransferRequest(TransferRequest request, Account source, Account destination) {
        if (request.getSourceAccountId().equals(request.getDestinationAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must be distinct");
        }
        if (!source.getCurrency().equalsIgnoreCase(destination.getCurrency())) {
            throw new CurrencyMismatchException("Account currency mismatch");
        }
    }

    public UUID[] getDeterministicLockOrder(UUID id1, UUID id2) {
        return id1.compareTo(id2) < 0 ? new UUID[]{id1, id2} : new UUID[]{id2, id1};
    }
}
