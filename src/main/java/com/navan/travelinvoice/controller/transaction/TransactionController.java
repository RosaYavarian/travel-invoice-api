package com.navan.travelinvoice.controller.transaction;

import com.navan.travelinvoice.model.dto.transaction.CreateTransactionRequest;
import com.navan.travelinvoice.model.dto.transaction.PatchTransactionRequest;
import com.navan.travelinvoice.model.dto.transaction.TransactionResponse;
import com.navan.travelinvoice.service.transaction.TransactionService;
import jakarta.validation.Valid;
import java.net.URI;

import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        TransactionResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/transactions/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable @Positive long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public TransactionResponse patch(@PathVariable @Positive long id, @RequestBody PatchTransactionRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
