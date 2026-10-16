package com.navan.travelinvoice.controller.invoice;

import com.navan.travelinvoice.model.dto.invoice.CreateInvoiceRequest;
import com.navan.travelinvoice.model.dto.invoice.InvoiceResponse;
import com.navan.travelinvoice.service.invoice.InvoiceService;
import java.net.URI;
import com.navan.travelinvoice.model.dto.common.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {
    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> create(@RequestBody CreateInvoiceRequest request) {
        InvoiceResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/invoices/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<InvoiceResponse> list(@RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(defaultValue = "0") int offset) {
        return service.list(limit, offset);
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public InvoiceResponse patch(@PathVariable long id, @RequestBody CreateInvoiceRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
