package com.team.aml.user.api;

import com.team.aml.user.domain.User;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** POST /admin/users/{id}/change-role 본문 (SCRUM-135 ②). 같은 역할·관리자에서 바꾸기는 User.changeRole 이 400 details.role 로. */
public record ChangeRoleRequest(
        @NotNull(message = User.ROLE_SWAP_ONLY)
        @Pattern(regexp = "INVESTIGATOR|APPROVER", message = User.ROLE_SWAP_ONLY) String role) {}
