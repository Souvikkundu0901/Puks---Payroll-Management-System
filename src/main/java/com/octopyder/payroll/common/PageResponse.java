package com.octopyder.payroll.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Standard list response wrapper conforming to API conventions:
 * {items, page, size, totalItems, totalPages}
 */
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {
    public static <T> PageResponse<T> of(Page<T> springPage) {
        return new PageResponse<>(
            springPage.getContent(),
            springPage.getNumber(),
            springPage.getSize(),
            springPage.getTotalElements(),
            springPage.getTotalPages()
        );
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalItems / size) : 0;
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }
}
