package com.navan.travelinvoice.model.dto.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int limit, int offset) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
