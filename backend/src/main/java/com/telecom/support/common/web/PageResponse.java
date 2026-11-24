package com.telecom.support.common.web;

import java.util.List;
import java.util.function.Function;

/** Server-side page envelope (XC-04, OUT-02.2 "show total matching records"). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        int pages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, pages);
    }

    public <R> PageResponse<R> map(Function<T, R> fn) {
        return new PageResponse<>(content.stream().map(fn).toList(), page, size, totalElements, totalPages);
    }
}
