package com.ledger;

import com.ledger.api.dto.AccountResponse;
import com.ledger.api.dto.CreateAccountRequest;
import com.ledger.api.dto.TransferRequest;
import com.ledger.api.dto.TransferResponse;
import com.ledger.domain.model.EntryType;
import com.ledger.domain.model.TransactionStatus;
import com.ledger.domain.repository.AccountRepository;
import com.ledger.domain.repository.LedgerEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TransferIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should execute atomic double-entry transfer and verify ledger invariants")
    void shouldExecuteAtomicDoubleEntryTransferSuccessfully() {
        // Setup Account A ($1,000) and Account B ($200)
        UUID accountA = createTestAccount("Company A", "USD", new BigDecimal("1000.0000"));
        UUID accountB = createTestAccount("Vendor B", "USD", new BigDecimal("200.0000"));

        TransferRequest transferRequest = TransferRequest.builder()
                .idempotencyKey("idemp-" + UUID.randomUUID())
                .sourceAccountId(accountA)
                .destinationAccountId(accountB)
                .amount(new BigDecimal("350.0000"))
                .currency("USD")
                .description("Vendor payment invoice #101")
                .build();

        ResponseEntity<TransferResponse> response = restTemplate.postForEntity(
                "/api/v1/transfers", transferRequest, TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(body.isIdempotentReplay()).isFalse();
        assertThat(body.getEntries()).hasSize(2);

        // Verify Account A balance: $1,000 - $350 = $650
        AccountResponse fetchedA = restTemplate.getForObject("/api/v1/accounts/" + accountA, AccountResponse.class);
        assertThat(fetchedA.getBalance()).isEqualByComparingTo(new BigDecimal("650.0000"));
        assertThat(fetchedA.isReconciled()).isTrue();

        // Verify Account B balance: $200 + $350 = $550
        AccountResponse fetchedB = restTemplate.getForObject("/api/v1/accounts/" + accountB, AccountResponse.class);
        assertThat(fetchedB.getBalance()).isEqualByComparingTo(new BigDecimal("550.0000"));
        assertThat(fetchedB.isReconciled()).isTrue();

        // Verify ledger entries conservation: DEBIT sum + CREDIT sum = 0
        long entriesCount = ledgerEntryRepository.countByTransactionId(body.getTransactionId());
        assertThat(entriesCount).isEqualTo(2);

        var entries = ledgerEntryRepository.findByTransactionIdOrderByCreatedAtAsc(body.getTransactionId());
        var debit = entries.stream().filter(e -> e.getEntryType() == EntryType.DEBIT).findFirst().orElseThrow();
        var credit = entries.stream().filter(e -> e.getEntryType() == EntryType.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAmount()).isEqualByComparingTo(new BigDecimal("350.0000"));
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("350.0000"));
        assertThat(debit.getAccount().getId()).isEqualTo(accountA);
        assertThat(credit.getAccount().getId()).isEqualTo(accountB);
    }

    @Test\n    @DisplayName("Should reject transfer when source account has insufficient funds")
    void shouldRejectTransferWhenInsufficientFunds() {
        UUID accountA = createTestAccount("Low Balance", "USD", new BigDecimal("25.0000"));
        UUID accountB = createTestAccount("Beneficiary", "USD", new BigDecimal("0.0000"));

        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idemp-insufficient-" + UUID.randomUUID())
                .sourceAccountId(accountA)
                .destinationAccountId(accountB)
                .amount(new BigDecimal("500.0000"))
                .currency("USD")
                .description("Overdraft attempt")
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/transfers", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("insufficient balance");

        // Balances remain untouched
        AccountResponse fetchedA = restTemplate.getForObject("/api/v1/accounts/" + accountA, AccountResponse.class);
        assertThat(fetchedA.getBalance()).isEqualByComparingTo(new BigDecimal("25.0000"));
    }

    @Test
    @DisplayName("Should reject transfer when accounts have mismatched currencies")
    void shouldRejectTransferWhenCurrencyMismatch() {
        UUID accountUsd = createTestAccount("USD Account", "USD", new BigDecimal("100.0000"));
        UUID accountEur = createTestAccount("EUR Account", "EUR", new BigDecimal("100.0000"));

        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idemp-curr-mismatch-" + UUID.randomUUID())
                .sourceAccountId(accountUsd)
                .destinationAccountId(accountEur)
                .amount(new BigDecimal("10.0000"))
                .currency("USD")
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/transfers", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("currency");
    }

    @Test
    @DisplayName("Should reject transfer to same account (self-transfer)")
    void shouldRejectSelfTransfer() {
        UUID account = createTestAccount("Self Sender", "USD", new BigDecimal("100.0000"));

        TransferRequest request = TransferRequest.builder()
                .idempotencyKey("idemp-self-" + UUID.randomUUID())
                .sourceAccountId(account)
                .destinationAccountId(account)
                .amount(new BigDecimal("10.0000"))
                .currency("USD")
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/transfers", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("distinct");
    }

    @Test
    @DisplayName("Should enforce DB trigger preventing direct UPDATE or DELETE on ledger entries")
    void shouldEnforceDatabaseLevelImmutability() {
        UUID accountA = createTestAccount("Audit A", "USD", new BigDecimal("100.0000"));
        UUID accountB = createTestAccount("Audit B", "USD", new BigDecimal("100.0000"));

        TransferRequest transferRequest = TransferRequest.builder()
                .idempotencyKey("idemp-immutability-" + UUID.randomUUID())
                .sourceAccountId(accountA)
                .destinationAccountId(accountB)
                .amount(new BigDecimal("20.0000"))
                .currency("USD")
                .build();

        ResponseEntity<TransferResponse> response = restTemplate.postForEntity(
                "/api/v1/transfers", transferRequest, TransferResponse.class);

        UUID entryId = response.getBody().getEntries().get(0).getId();

        // Direct UPDATE attempt should fail via DB trigger
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET amount = 9999.0000 WHERE id = ?", entryId))
                .hasMessageContaining("Modifications (UPDATE or DELETE) are strictly prohibited on immutable ledger table ledger_entries");

        // Direct DELETE attempt should fail via DB trigger
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .hasMessageContaining("Modifications (UPDATE or DELETE) are strictly prohibited on immutable ledger table ledger_entries");
    }

    private UUID createTestAccount(String name, String currency, BigDecimal initialBalance) {
        CreateAccountRequest request = CreateAccountRequest.builder()
                .name(name)
                .currency(currency)
                .initialBalance(initialBalance)
                .build();

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts", request, AccountResponse.class);
        return response.getBody().getId();
    }
}

