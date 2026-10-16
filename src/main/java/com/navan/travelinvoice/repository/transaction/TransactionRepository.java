package com.navan.travelinvoice.repository.transaction;

import com.navan.travelinvoice.model.entity.transaction.Transaction;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByExternalId(String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") long id);

    @Query(value = "SELECT id FROM transactions ORDER BY id ASC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Long> findPageIds(@Param("limit") int limit, @Param("offset") int offset);

    @Query("select distinct p from Transaction p left join fetch p.taxLines where p.id in :ids")
    List<Transaction> fetchWithTaxLines(@Param("ids") List<Long> ids);

    @Query("select distinct p from Transaction p left join fetch p.feeLines where p.id in :ids")
    List<Transaction> fetchWithFeeLines(@Param("ids") List<Long> ids);

    interface IdentityResult {
        Long getId();
        Boolean getInserted();
    }

    // PostgreSQL serializes conflict resolution with concurrent inserts/deletes.
    // A conflict performs a no-op update only to return and lock the existing row;
    // the caller immediately rolls that transaction back on the duplicate path.
    // PostgreSQL 17's xmax distinguishes the insert branch from the conflict branch.
    @Query(value = """
            INSERT INTO transactions (external_id, type, currency, total, occurred_at, metadata)
            VALUES (:externalId, :type, :currency, :total, :occurredAt, CAST(:metadata AS jsonb))
            ON CONFLICT (external_id) DO UPDATE SET external_id = EXCLUDED.external_id
            RETURNING id, (xmax = 0) AS inserted
            """, nativeQuery = true)
    IdentityResult insertOrIdentify(@Param("externalId") String externalId, @Param("type") String type,
            @Param("currency") String currency, @Param("total") BigDecimal total,
            @Param("occurredAt") Instant occurredAt, @Param("metadata") String metadata);
}
