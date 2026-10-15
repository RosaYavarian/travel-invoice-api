package com.navan.travelinvoice.model.dto.transaction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.navan.travelinvoice.exception.transaction.InvalidTransactionException;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class PatchTransactionRequest {
    private static final Set<String> ALLOWED = Set.of("total", "occurred_at", "tax_lines", "fee_lines", "metadata");
    private final ObjectNode fields;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PatchTransactionRequest(JsonNode input) {
        if (input == null || !input.isObject()) {
            throw new InvalidTransactionException("PATCH body must be a JSON object");
        }
        for (var entry : input.properties()) {
            if (!ALLOWED.contains(entry.getKey())) {
                throw new InvalidTransactionException("Field cannot be patched: " + entry.getKey());
            }
            if (entry.getValue().isNull()) {
                throw new InvalidTransactionException("Explicit null is not allowed for " + entry.getKey());
            }
        }
        this.fields = ((ObjectNode) input).deepCopy();
    }

    public boolean has(String field) {
        return fields.has(field);
    }

    public CreateTransactionRequest applyTo(CreateTransactionRequest current, JsonMapper mapper) {
        ObjectNode merged = (ObjectNode) mapper.valueToTree(current);
        fields.properties().forEach(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
        try {
            return mapper.treeToValue(merged, CreateTransactionRequest.class);
        } catch (JacksonException exception) {
            throw new InvalidTransactionException("Invalid PATCH field type or value");
        }
    }
}
