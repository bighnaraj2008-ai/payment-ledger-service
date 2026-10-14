package com.ledger.service;

import com.ledger.api.dto.LedgerEntryDto;
import com.ledger.api.dto.TransferRequest;
import com.ledger.api.dto.TransferResponse;
import com.ledger.domain.exception.AccountNotFoundException;
import com.ledger.domain.exception.CurrencyMismatchException;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.EntryType;
import com.ledger.domain.model.LedgerEntry;
import com.ledger.domain.model.Transaction;
import com.ledger.domain.model.TransactionStatus;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.LedgerEntryRepository;
import com.ledger.domain.repository.TransactionRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    public static final int MAX_RETRIES = 12;
    public static final long BASE_DELAY_MS = 10;

    private final TransferExecutor transferExecutor;
    private final AccountRepository accountRepository;

    /**
     * Executes a double-entry transfer with idempotency protection and
     * an exponential backoff retry loop for optimistic locking collisions.
     */
    public TransferResponse transfer(TransferRequest request)
            throws ObjectOptimisticLockingFailureException, PessimisticLockingFailureException {
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be null or blank");
        }

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return transferExecutor.executeTransfer(request);
            } catch (OptimisticLockException | TransientDataAccessException ex) {

                if (attempt >= MAX_RETRIES) {
                    log.error("Transfer failed after reaching max retries ({}) for idempotencyKey='{}': {}",
                            MAX_RETRIES, request.getIdempotencyKey(), ex.getMessage());
                    throw ex;
                }

                long backoffMs = computeBackoff(attempt);
                log.warn("Concurrency contention detected (attempt {}/{}) for idempotencyKey='{}'. Retrying in {}ms...",
                        attempt, MAX_RETRIES, request.getIdempotencyKey(), backoffMs);

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Transfer execution thread interrupted during retry backoff", ie);
                }
            }
        }
    }

    /**
     * Exponential backoff with full jitter to distribute retries evenly across
     * concurrent workers.
     */
    private long computeBackoff(int attempt) {
        long exponential = (long) (BASE_DELAY_MS * Math.pow(1.6, attempt));
        long jitter = ThreadLocalRandom.current().nextLong(5, 25);
        return Math.min(exponential + jitter, 1500);
    }

    /**
     * Verifies that the account's cached balance strictly equals the net sum of all
     * its ledger entries.
     */
    public boolean verifyAccountBalanceReconciliation(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        BigDecimal calculatedSum = accountRepository.calculateNetLedgerSum(accountId);
        boolean matches = account.getBalance().compareTo(calculatedSum) == 0;

        if (!matches) {
            log.error("AUDIT MISMATCH for account {}: cached balance = {}, calculated ledger sum = {}",
                    accountId, account.getBalance(), calculatedSum);
        }
        return matches;
    }

    /**
     * Internal isolated transaction executor for double-entry persistence.
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class TransferExecutor {

        private final AccountRepository accountRepository;
        private final TransactionRepository transactionRepository;
        private final LedgerEntryRepository ledgerEntryRepository;

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public TransferResponse executeTransfer(TransferRequest request) {
            String idempotencyKey = request.getIdempotencyKey();

            // 1. Idempotency Pre-Check
            Optional<Transaction> existingTxOpt = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existingTxOpt.isPresent()) {
                log.info("Idempotent request detected for key '{}'. Returning existing transaction.", idempotencyKey);
                return buildExistingTransferResponse(existingTxOpt.get(), true);
            }

            // 2. Self-Transfer Check
            if (request.getSourceAccountId().equals(request.getDestinationAccountId())) {
                throw new IllegalArgumentException(String.format(
                        "Source and destination accounts must be distinct. Account '%s' was specified for both",
                        request.getSourceAccountId()));
            }

            // 3. Deterministic Account Loading Order (avoids DB deadlocks under high
            // concurrency)
            UUID firstId = request.getSourceAccountId().compareTo(request.getDestinationAccountId()) < 0
                    ? request.getSourceAccountId()
                    : request.getDestinationAccountId();
            UUID secondId = request.getSourceAccountId().compareTo(request.getDestinationAccountId()) < 0
                    ? request.getDestinationAccountId()
                    : request.getSourceAccountId();

            Account firstAccount = accountRepository.findById(firstId)
                    .orElseThrow(() -> new AccountNotFoundException(firstId));
            Account secondAccount = accountRepository.findById(secondId)
                    .orElseThrow(() -> new AccountNotFoundException(secondId));

            Account sourceAccount = request.getSourceAccountId().equals(firstAccount.getId()) ? firstAccount
                    : secondAccount;
            Account destinationAccount = request.getDestinationAccountId().equals(firstAccount.getId()) ? firstAccount
                    : secondAccount;

            // 4. Currency Validation
            if (!sourceAccount.getCurrency().equalsIgnoreCase(destinationAccount.getCurrency())) {
                throw new CurrencyMismatchException(String.format(
                        "Source account currency '%s' does not match destination account currency '%s'",
                        sourceAccount.getCurrency(), destinationAccount.getCurrency()));
            }
            if (!sourceAccount.getCurrency().equalsIgnoreCase(request.getCurrency())) {
                throw new CurrencyMismatchException(String.format(
                        "Transfer currency '%s' does not match account currency '%s'",
                        request.getCurrency(), sourceAccount.getCurrency()));
            }

            // 5. Apply Account Balance Updates (Debit & Credit)
            BigDecimal transferAmount = request.getAmount().setScale(Account.SCALE, Account.ROUNDING);
            sourceAccount.debit(transferAmount);
            destinationAccount.credit(transferAmount);

            accountRepository.save(sourceAccount);
            accountRepository.save(destinationAccount);

            // 6. Persist Transaction Header (Immutable)
            Transaction transaction = new Transaction(
                    UUID.randomUUID(),
                    idempotencyKey,
                    request.getDescription(),
                    TransactionStatus.COMPLETED);

            try {
                transaction = transactionRepository.save(transaction);
            } catch (DataIntegrityViolationException ex) {
                // Concurrent duplicate idempotency key race condition caught by PostgreSQL
                // unique constraint
                log.warn("Concurrent duplicate idempotency key detected for '{}'. Resolving committed transaction.",
                        idempotencyKey);
                return transactionRepository.findByIdempotencyKey(idempotencyKey)
                        .map(tx -> buildExistingTransferResponse(tx, true))
                        .orElseThrow(() -> ex);
            }

            // 7. Double-Entry Invariant: Atomically create matching DEBIT and CREDIT ledger
            // entries
            LedgerEntry debitEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    transaction,
                    sourceAccount,
                    EntryType.DEBIT,
                    transferAmount);

            LedgerEntry creditEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    transaction,
                    destinationAccount,
                    EntryType.CREDIT,
                    transferAmount);

            ledgerEntryRepository.save(debitEntry);
            ledgerEntryRepository.save(creditEntry);

            log.debug("Transfer executed successfully: txId={}, debitAccount={}, creditAccount={}, amount={}",
                    transaction.getId(), sourceAccount.getId(), destinationAccount.getId(), transferAmount);

            return TransferResponse.builder()
                    .transactionId(transaction.getId())
                    .idempotencyKey(transaction.getIdempotencyKey())
                    .sourceAccountId(sourceAccount.getId())
                    .destinationAccountId(destinationAccount.getId())
                    .amount(transferAmount)
                    .currency(sourceAccount.getCurrency())
                    .status(transaction.getStatus())
                    .description(transaction.getDescription())
                    .createdAt(transaction.getCreatedAt())
                    .idempotentReplay(false)
                    .entries(List.of(
                            toDto(debitEntry),
                            toDto(creditEntry)))
                    .build();
        }

        @Transactional(readOnly = true)
        public TransferResponse buildExistingTransferResponse(Transaction transaction, boolean isReplay) {
            List<LedgerEntry> entries = ledgerEntryRepository
                    .findByTransactionIdOrderByCreatedAtAsc(transaction.getId());

            UUID sourceId = null;
            UUID destId = null;
            BigDecimal amount = BigDecimal.ZERO;
            String currency = "USD";

            for (LedgerEntry entry : entries) {
                if (entry.getEntryType() == EntryType.DEBIT) {
                    sourceId = entry.getAccount().getId();
                    amount = entry.getAmount();
                    currency = entry.getAccount().getCurrency();
                } else if (entry.getEntryType() == EntryType.CREDIT) {
                    destId = entry.getAccount().getId();
                    if (amount.compareTo(BigDecimal.ZERO) == 0) {
                        amount = entry.getAmount();
                    }
                    currency = entry.getAccount().getCurrency();
                }
            }

            return TransferResponse.builder()
                    .transactionId(transaction.getId())
                    .idempotencyKey(transaction.getIdempotencyKey())
                    .sourceAccountId(sourceId)
                    .destinationAccountId(destId)
                    .amount(amount)
                    .currency(currency)
                    .status(transaction.getStatus())
                    .description(transaction.getDescription())
                    .createdAt(transaction.getCreatedAt())
                    .idempotentReplay(isReplay)
                    .entries(entries.stream().map(this::toDto).toList())
                    .build();
        }

        private LedgerEntryDto toDto(LedgerEntry entry) {
            return LedgerEntryDto.builder()
                    .id(entry.getId())
                    .accountId(entry.getAccount().getId())
                    .entryType(entry.getEntryType())
                    .amount(entry.getAmount())
                    .createdAt(entry.getCreatedAt())
                    .build();
        }
    }
}

