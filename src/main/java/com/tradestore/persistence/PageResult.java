package com.tradestore.persistence;

import java.util.List;

/** One page of results, with the paging metadata the API returns (US-403). */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(content);
    }

    public long totalPages() {
        return size == 0 ? 0 : (totalElements + size - 1) / size;
    }
}
