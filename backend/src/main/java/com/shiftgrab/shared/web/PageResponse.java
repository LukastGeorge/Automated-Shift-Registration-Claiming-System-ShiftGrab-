package com.shiftgrab.shared.web;

import java.util.List;
import java.util.function.Function;

/** Stable JSON shape for paginated lists, independent of Spring Data's {@code Page} serialisation. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResponse<>(content, page, size, totalElements, (int) ((totalElements + size - 1) / size));
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
