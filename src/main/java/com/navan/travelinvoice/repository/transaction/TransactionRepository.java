package com.navan.travelinvoice.repository.transaction;

import com.navan.travelinvoice.model.entity.transaction.Transaction;
import java.util.Optional;
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
}
