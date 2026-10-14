package com.ledger;

import com.ledger.api.dto.AccountResponse;
import com.ledger.api.dto.CreateAccountRequest;
import com.ledger.domain.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AccountIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Should successfully create an account with zero initial balance")
    void shouldCreateAccountWithZeroBalance() {
        CreateAccountRequest request = CreateAccountRequest.builder()
                .name("Alice Operational Account")
                .currency("USD")
                .initialBalance(BigDecimal.ZERO)
                .build();

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts", request, AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isNotNull();
        assertThat(body.getName()).isEqualTo("Alice Operational Account");
        assertThat(body.getCurrency()).isEqualTo("USD");
        assertThat(body.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.getCalculatedLedgerSum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.isReconciled()).isTrue();
    }

    @Test
    @DisplayName("Should create account with initial balance and verify ledger sum reconciliation")
    void shouldCreateAccountWithInitialBalanceAndReconcile() {
        BigDecimal initialFunds = new BigDecimal("1500.5000");
        CreateAccountRequest request = CreateAccountRequest.builder()
                .name("Treasury Reserve")
                .currency("USD")
                .initialBalance(initialFunds)
                .build();

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts", request, AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getBalance()).isEqualByComparingTo(initialFunds);
        assertThat(body.getCalculatedLedgerSum()).isEqualByComparingTo(initialFunds);
        assertThat(body.isReconciled()).isTrue();

        // Verify via repository directly
        BigDecimal ledgerSum = accountRepository.calculateNetLedgerSum(body.getId());
        assertThat(ledgerSum).isEqualByComparingTo(initialFunds);

        Boolean isReconciled = accountRepository.isBalanceReconciled(body.getId());
        assertThat(isReconciled).isTrue();
    }

    @Test
    @DisplayName("Should fetch account by ID and return reconciled balance")
    void shouldFetchAccountById() {
        CreateAccountRequest createReq = CreateAccountRequest.builder()
                .name("Bob Savings")
                .currency("EUR")
                .initialBalance(new BigDecimal("250.0000"))
                .build();

        ResponseEntity<AccountResponse> createResp = restTemplate.postForEntity(
                "/api/v1/accounts", createReq, AccountResponse.class);
        UUID accountId = createResp.getBody().getId();

        ResponseEntity<AccountResponse> fetchResp = restTemplate.getForEntity(
                "/api/v1/accounts/" + accountId, AccountResponse.class);

        assertThat(fetchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetchResp.getBody()).isNotNull();
        assertThat(fetchResp.getBody().getId()).isEqualTo(accountId);
        assertThat(fetchResp.getBody().getName()).isEqualTo("Bob Savings");
        assertThat(fetchResp.getBody().getCurrency()).isEqualTo("EUR");
        assertThat(fetchResp.getBody().getBalance()).isEqualByComparingTo(new BigDecimal("250.0000"));
        assertThat(fetchResp.getBody().isReconciled()).isTrue();
    }

    @Test
    @DisplayName("Should return 404 for non-existent account")
    void shouldReturn404ForUnknownAccount() {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/accounts/" + unknownId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("not found");
    }
}
