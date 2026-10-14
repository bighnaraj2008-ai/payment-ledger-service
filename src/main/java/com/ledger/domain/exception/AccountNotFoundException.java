package com.ledger.domain.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super(String.format("Account with id '%s' not found", accountId));
    }
}
