package com.navan.travelinvoice.controller.invoice;

import com.navan.travelinvoice.model.dto.invoice.CreateInvoiceRequest;
import com.navan.travelinvoice.model.dto.invoice.InvoiceResponse;
import com.navan.travelinvoice.service.invoice.InvoiceService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable long id) {
        return service.get(id);
    }
}
