package com.navan.travelinvoice.repository.transaction;

import com.navan.travelinvoice.model.entity.transaction.Transaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByExternalId(String externalId);
}
