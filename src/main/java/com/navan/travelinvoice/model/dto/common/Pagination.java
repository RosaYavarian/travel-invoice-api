package com.navan.travelinvoice.model.dto.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Pagination {
    private Pagination() {
    }

    public static void validate(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100 and offset must be nonnegative");
        }
    }
}
