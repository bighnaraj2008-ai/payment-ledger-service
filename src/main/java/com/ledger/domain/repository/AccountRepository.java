package com.ledger.domain.repository;

import com.ledger.domain.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Calculates the net sum of all ledger entries for this account.
     * In standard bookkeeping:
     *   CREDIT entries add to balance (+amount)
     *   DEBIT entries subtract from balance (-amount)
     */
    @Query("""
        SELECT COALESCE(
            SUM(
                CASE 
                    WHEN e.entryType = com.ledger.domain.model.EntryType.CREDIT THEN e.amount 
                    ELSE -e.amount 
                END
            ), 
            0.0000
        ) 
        FROM LedgerEntry e 
        WHERE e.account.id = :accountId
        """)
    BigDecimal calculateNetLedgerSum(@Param("accountId") UUID accountId);

    /**
     * Verifies if an account's cached balance strictly matches the calculated net sum of its ledger entries.
     */
    @Query("""
        SELECT CASE 
            WHEN a.balance = COALESCE(
                (SELECT SUM(
                    CASE 
                        WHEN e.entryType = com.ledger.domain.model.EntryType.CREDIT THEN e.amount 
                        ELSE -e.amount 
                    END
                ) 
                FROM LedgerEntry e 
                WHERE e.account.id = a.id), 
                0.0000
            ) 
            THEN true 
            ELSE false 
        END 
        FROM Account a 
        WHERE a.id = :accountId
        """)
    Boolean isBalanceReconciled(@Param("accountId") UUID accountId);
}
