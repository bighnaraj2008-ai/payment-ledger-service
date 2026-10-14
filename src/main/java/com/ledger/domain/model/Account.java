package com.ledger.domain.model;

import com.ledger.domain.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    public static final int SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = SCALE)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Account(UUID id, String name, String currency, BigDecimal initialBalance) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name;
        this.currency = currency != null ? currency.toUpperCase() : "USD";
        this.balance = initialBalance != null 
                ? initialBalance.setScale(SCALE, ROUNDING) 
                : BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        this.version = 0L;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void debit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING);
        if (this.balance.compareTo(normalizedAmount) < 0) {
            throw new InsufficientFundsException(this.id, this.balance, normalizedAmount);
        }
        this.balance = this.balance.subtract(normalizedAmount).setScale(SCALE, ROUNDING);
        this.updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING);
        this.balance = this.balance.add(normalizedAmount).setScale(SCALE, ROUNDING);
        this.updatedAt = Instant.now();
    }
}
