package com.team.aml.user.api;

import com.team.aml.shared.api.PageResponse;
import com.team.aml.user.application.AdminUserView;
import com.team.aml.user.application.ListUsers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 계정 목록 (SCRUM-135 ①). ADMIN 권한은 SecurityConfig 에서 검사. 접근 기록은 남기지 않는다. */
@Tag(name = "admin-users")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final ListUsers listUsers;

    public AdminUserController(ListUsers listUsers) {
        this.listUsers = listUsers;
    }

    @Operation(summary = "관리자 계정 목록",
            description = "role 은 선택, is_active 미지정은 활성만. display_name·id 오름차순 고정. size 기본 20, 최대 100")
    @GetMapping
    public PageResponse<AdminUserView> list(@Valid @ParameterObject @ModelAttribute AdminUserListQuery q) {
        return PageResponse.from(listUsers.execute(q.role(), q.activeOrDefault(), q.pageOrDefault(), q.sizeOrDefault()));
    }
}
