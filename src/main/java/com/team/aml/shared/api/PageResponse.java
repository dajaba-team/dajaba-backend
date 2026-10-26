package com.team.aml.shared.api;

import org.springframework.data.domain.Page;

import java.util.List;

/** 목록 응답 공통 틀. 빈 목록은 null 이 아니라 []. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public PageResponse {
        if (items == null) items = List.of();
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
