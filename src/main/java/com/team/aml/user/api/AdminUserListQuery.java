package com.team.aml.user.api;

import com.team.aml.user.domain.Role;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.BindParam;

/** GET /admin/users 쿼리 (SCRUM-135 ①). 정렬은 고정, 형식 오류는 400 INVALID_REQUEST. */
public record AdminUserListQuery(
        Role role,
        @Parameter(name = "is_active") @BindParam("is_active") Boolean isActive,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size) {

    static final int DEFAULT_SIZE = 20;

    boolean activeOrDefault() {
        return isActive == null || isActive;
    }

    int pageOrDefault() {
        return page == null ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
