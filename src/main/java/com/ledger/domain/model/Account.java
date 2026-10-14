package com.ledger.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Account(UUID id, String name, String currency, BigDecimal initialBalance) {
        this.id = id != null ? id : UUID.randomUUID();
        this.name = name;
        this.currency = currency != null ? currency.toUpperCase() : "USD";
        this.balance = initialBalance != null ? initialBalance.setScale(SCALE, ROUNDING) : BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
