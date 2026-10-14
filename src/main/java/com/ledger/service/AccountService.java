package com.ledger.service;

import com.ledger.api.dto.AccountResponse;
import com.ledger.api.dto.CreateAccountRequest;
import com.ledger.domain.exception.AccountNotFoundException;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.EntryType;
import com.ledger.domain.model.LedgerEntry;
import com.ledger.domain.model.Transaction;
import com.ledger.domain.model.TransactionStatus;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.LedgerEntryRepository;
import com.ledger.domain.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        UUID accountId = UUID.randomUUID();
        BigDecimal initialBalance = request.getInitialBalance() != null 
                ? request.getInitialBalance() 
                : BigDecimal.ZERO;

        Account account = new Account(
                accountId,
                request.getName(),
                request.getCurrency(),
                initialBalance
        );
        account = accountRepository.save(account);

        // If funded with an initial balance, record an initial ledger entry to maintain 100% auditability
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            String genesisKey = "genesis-" + accountId;
            Transaction genesisTx = new Transaction(
                    UUID.randomUUID(),
                    genesisKey,
                    "Initial Account Funding",
                    TransactionStatus.COMPLETED
            );
            genesisTx = transactionRepository.save(genesisTx);

            LedgerEntry genesisEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    genesisTx,
                    account,
                    EntryType.CREDIT,
                    initialBalance
            );
            ledgerEntryRepository.save(genesisEntry);
            log.info("Initialized account {} with balance {} backed by ledger entry {}", 
                    accountId, initialBalance, genesisEntry.getId());
        }

        BigDecimal calculatedSum = accountRepository.calculateNetLedgerSum(accountId);
        boolean reconciled = account.getBalance().compareTo(calculatedSum) == 0;

        return mapToResponse(account, calculatedSum, reconciled);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        BigDecimal calculatedSum = accountRepository.calculateNetLedgerSum(id);
        boolean reconciled = account.getBalance().compareTo(calculatedSum) == 0;

        return mapToResponse(account, calculatedSum, reconciled);
    }

    private AccountResponse mapToResponse(Account account, BigDecimal calculatedSum, boolean reconciled) {
        return AccountResponse.builder()
                .id(account.getId())
                .name(account.getName())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .calculatedLedgerSum(calculatedSum)
                .reconciled(reconciled)
                .version(account.getVersion())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }
}
