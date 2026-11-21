package com.tradestore.api;

import java.util.List;
import java.util.function.Function;

import com.tradestore.persistence.PageResult;

/** A page of results with its paging metadata (docs/05-lld.md section 6.1). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, long totalPages) {

    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(result.content().stream().map(mapper).toList(), result.page(), result.size(),
                result.totalElements(), result.totalPages());
    }
}
