package com.navan.travelinvoice.repository.invoice;

import com.navan.travelinvoice.model.entity.invoice.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    @EntityGraph(attributePaths = "lines")
    Optional<Invoice> findWithLinesById(long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") long id);

    @Query(value = "SELECT id FROM invoices ORDER BY id ASC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Long> findPageIds(@Param("limit") int limit, @Param("offset") int offset);

    @Query("select distinct p from Invoice p left join fetch p.lines where p.id in :ids")
    List<Invoice> fetchWithLines(@Param("ids") List<Long> ids);
}
