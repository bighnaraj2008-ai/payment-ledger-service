package com.ledger.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    private final UUID accountId;
    private final BigDecimal currentBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(UUID accountId, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super(String.format("Account '%s' has insufficient balance (%s) for debit of %s",
                accountId, currentBalance, requestedAmount));
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }
}
