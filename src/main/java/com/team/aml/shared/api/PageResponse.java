package com.team.aml.shared.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 목록 응답 공통 틀 (레퍼런스 §12). {@code {items, page, size, total}}.
 * 빈 목록은 null 이 아니라 []. 전체 페이지 수는 프론트가 ceil(total / size)로 계산.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public PageResponse {
        if (items == null) items = List.of();
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
