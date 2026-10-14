package com.ledger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
    private UUID id;
    private String name;
    private String currency;
    private BigDecimal balance;
    private BigDecimal calculatedLedgerSum;
    private boolean reconciled;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
