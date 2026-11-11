package com.team.aml.user.api;

import com.team.aml.shared.api.PageResponse;
import com.team.aml.user.application.ActivateUser;
import com.team.aml.user.application.AdminUserView;
import com.team.aml.user.application.ChangeUserRole;
import com.team.aml.user.application.CreateUser;
import com.team.aml.user.application.DeactivateUser;
import com.team.aml.user.application.ListUsers;
import com.team.aml.user.application.ResetUserPassword;
import com.team.aml.user.domain.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 계정 목록·관리 (SCRUM-135 ①·②, 04 §7). ADMIN 권한은 SecurityConfig 에서 검사. 상태 변경은 POST /{id}/{동사}만.
 * 응답은 전부 계정 한 줄(목록 항목 모양). 관리자 본인·SYSTEM 대상은 409 ACCOUNT_CHANGE_NOT_ALLOWED.
 */
@Tag(name = "admin-users")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final ListUsers listUsers;
    private final CreateUser createUser;
    private final ChangeUserRole changeUserRole;
    private final DeactivateUser deactivateUser;
    private final ActivateUser activateUser;
    private final ResetUserPassword resetUserPassword;

    public AdminUserController(ListUsers listUsers, CreateUser createUser, ChangeUserRole changeUserRole,
                               DeactivateUser deactivateUser, ActivateUser activateUser, ResetUserPassword resetUserPassword) {
        this.listUsers = listUsers;
        this.createUser = createUser;
        this.changeUserRole = changeUserRole;
        this.deactivateUser = deactivateUser;
        this.activateUser = activateUser;
        this.resetUserPassword = resetUserPassword;
    }

    @Operation(summary = "관리자 계정 목록",
            description = "role 은 선택, is_active 미지정은 활성만. display_name·id 오름차순 고정. size 기본 20, 최대 100. "
                    + "active_case_count 는 조사역 행만 진행 중 담당 건수, 다른 역할은 null")
    @GetMapping
    public PageResponse<AdminUserView> list(@Valid @ParameterObject @ModelAttribute AdminUserListQuery q) {
        return PageResponse.from(listUsers.execute(q.role(), q.activeOrDefault(), q.pageOrDefault(), q.sizeOrDefault()));
    }

    @Operation(summary = "계정 발급", description = "201 + 한 줄. 아이디 중복 409 LOGIN_ID_TAKEN. 비밀번호 8~128자, UTF-8 72바이트까지")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserView create(@Valid @RequestBody CreateUserRequest req) {
        return createUser.execute(req.loginId(), req.displayName(), Role.valueOf(req.role()), req.initialPassword());
    }

    @Operation(summary = "역할 변경", description = "조사역 ↔ 승인자만. 진행 중 담당이 남으면 409 ASSIGNEE_HAS_ACTIVE_CASES. 그 사람 세션 끊김")
    @PostMapping("/{id}/change-role")
    public AdminUserView changeRole(@PathVariable("id") long id, @Valid @RequestBody ChangeRoleRequest req) {
        return changeUserRole.execute(id, Role.valueOf(req.role()));
    }

    @Operation(summary = "비활성화", description = "담당이 남아도 됨 — 응답 active_case_count. 세션 끊김. 이미 비활성이면 그대로 200")
    @PostMapping("/{id}/deactivate")
    public AdminUserView deactivate(@PathVariable("id") long id) {
        return deactivateUser.execute(id);
    }

    @Operation(summary = "재활성화", description = "이미 활성이면 그대로 200")
    @PostMapping("/{id}/activate")
    public AdminUserView activate(@PathVariable("id") long id) {
        return activateUser.execute(id);
    }

    @Operation(summary = "비밀번호 초기화", description = "8~128자, UTF-8 72바이트까지. 세션 끊김")
    @PostMapping("/{id}/reset-password")
    public AdminUserView resetPassword(@PathVariable("id") long id, @Valid @RequestBody ResetPasswordRequest req) {
        return resetUserPassword.execute(id, req.newPassword());
    }
}
