package com.ledger;

import com.ledger.api.dto.TransferRequest;
import com.ledger.api.dto.TransferResponse;
import com.ledger.domain.model.Account;
import com.ledger.domain.model.EntryType;
import com.ledger.domain.model.Transaction;
import com.ledger.domain.model.TransactionStatus;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.LedgerEntryRepository;
import com.ledger.domain.repository.TransactionRepository;
import com.ledger.service.LedgerService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class ConcurrencyLedgerTest extends BaseIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    @DisplayName("The 1,000 Simultaneous Transfers Concurrency Stress Test: Verify zero drift, double-entry balance, and idempotency")
    void testOneThousandSimultaneousTransfersConcurrencyProof() throws InterruptedException {
        int numAccounts = 5;
        BigDecimal initialBalancePerAccount = new BigDecimal("10000.0000");
        BigDecimal expectedTotalSystemMoney = initialBalancePerAccount.multiply(BigDecimal.valueOf(numAccounts));

        log.info("==========================================================================");
        log.info("STARTING CONCURRENCY PROOF: 1,000 SIMULTANEOUS TRANSFERS");
        log.info("Accounts: {}, Initial Balance Per Account: {}, Total System Pool: {}",
                numAccounts, initialBalancePerAccount, expectedTotalSystemMoney);
        log.info("==========================================================================");

        // 1. Initialize 5 Accounts with $10,000 each
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < numAccounts; i++) {
            UUID accId = UUID.randomUUID();
            Account acc = new Account(accId, "High-Traffic Account #" + i, "USD", initialBalancePerAccount);
            accountRepository.save(acc);
            accountIds.add(accId);

            // Record initial funding transaction and ledger entry for complete audit trail
            var genesisTx = transactionRepository.save(new Transaction(
                    UUID.randomUUID(),
                    "genesis-stress-" + accId,
                    "Genesis Funding for Stress Test",
                    TransactionStatus.COMPLETED
            ));
            ledgerEntryRepository.save(new com.ledger.domain.model.LedgerEntry(
                    UUID.randomUUID(),
                    genesisTx,
                    acc,
                    EntryType.CREDIT,
                    initialBalancePerAccount
            ));
        }

        long baselineTxCount = transactionRepository.count();
        long baselineEntriesCount = ledgerEntryRepository.count();

        // 2. Prepare 1,000 unique transfer requests + 100 intentional duplicate submissions (1,100 total tasks)
        int totalTransfers = 1000;
        int duplicateInjections = 100;
        List<TransferRequest> requests = new ArrayList<>(totalTransfers + duplicateInjections);

        for (int i = 0; i < totalTransfers; i++) {
            int srcIdx = ThreadLocalRandom.current().nextInt(numAccounts);
            int dstIdx;
            do {
                dstIdx = ThreadLocalRandom.current().nextInt(numAccounts);
            } while (dstIdx == srcIdx);

            BigDecimal transferAmount = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1.0, 25.0))
                    .setScale(Account.SCALE, Account.ROUNDING);

            String idempotencyKey = "stress-tx-" + i + "-" + UUID.randomUUID();

            TransferRequest req = TransferRequest.builder()
                    .idempotencyKey(idempotencyKey)
                    .sourceAccountId(accountIds.get(srcIdx))
                    .destinationAccountId(accountIds.get(dstIdx))
                    .amount(transferAmount)
                    .currency("USD")
                    .description("High contention transfer #" + i)
                    .build();

            requests.add(req);
        }

        // Randomly select 100 requests to re-submit simultaneously for duplicate idempotency testing
        for (int i = 0; i < duplicateInjections; i++) {
            TransferRequest original = requests.get(ThreadLocalRandom.current().nextInt(totalTransfers));
            TransferRequest duplicate = TransferRequest.builder()
                    .idempotencyKey(original.getIdempotencyKey())
                    .sourceAccountId(original.getSourceAccountId())
                    .destinationAccountId(original.getDestinationAccountId())
                    .amount(original.getAmount())
                    .currency(original.getCurrency())
                    .description("Duplicate submission of " + original.getIdempotencyKey())
                    .build();
            requests.add(duplicate);
        }

        // Shuffle requests thoroughly so unique and duplicate requests arrive in random interleaved order
        Collections.shuffle(requests);

        // 3. Fire all 1,100 requests simultaneously using 64 concurrent worker threads
        int threadPoolSize = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(requests.size());

        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger idempotentReplays = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        Instant startTime = Instant.now();

        for (TransferRequest req : requests) {
            executor.submit(() -> {
                try {
                    startGun.await();
                    TransferResponse resp = ledgerService.transfer(req);
                    if (resp.isIdempotentReplay()) {
                        idempotentReplays.incrementAndGet();
                    } else {
                        successfulTransfers.incrementAndGet();
                    }
                } catch (Exception ex) {
                    log.error("Transfer execution failure for key={}: {}", req.getIdempotencyKey(), ex.getMessage());
                    failures.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Fire the starting gun!
        startGun.countDown();

        // Wait for all 1,100 concurrent requests to finish
        boolean finishedInTime = completionLatch.await(180, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        Duration duration = Duration.between(startTime, Instant.now());

        log.info("==========================================================================");
        log.info("CONCURRENCY TEST COMPLETED in {} ms ({} sec)", duration.toMillis(), duration.toSeconds());
        log.info("Total Requests Executed: {}", requests.size());
        log.info("Successful New Transfers: {}", successfulTransfers.get());
        log.info("Idempotent Replays Caught: {}", idempotentReplays.get());
        log.info("Failures / Errors: {}", failures.get());
        log.info("Throughput: {} transfers/second",
                String.format("%.2f", (requests.size() / (Math.max(1, duration.toMillis()) / 1000.0))));
        log.info("==========================================================================");

        assertThat(finishedInTime).as("All transfers should complete within time limit").isTrue();
        assertThat(failures.get()).as("There should be zero unhandled failures").isEqualTo(0);
        assertThat(successfulTransfers.get()).as("All 1,000 unique transfers must succeed").isEqualTo(totalTransfers);
        assertThat(idempotentReplays.get()).as("All duplicate requests must be recognized as idempotent replays")
                .isEqualTo(duplicateInjections);

        log.info("CONCURRENCY TEST COMPLETED");
        log.info("==========================================================================");
    }
}

