package com.team.aml.user.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /admin/users 본문 (SCRUM-135 ②, 11/7 입력 규칙 — 계약서·화면과 같음).
 * display_name 은 앞뒤 공백을 빼고 1~50자. role 은 SYSTEM 이 아닌 셋. toString 에 비밀번호를 넣지 않는다.
 */
public record CreateUserRequest(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$", message = "영문·숫자·_·- 3~64자") String loginId,
        @NotNull @Size(min = 1, max = 50, message = "앞뒤 공백을 뺀 1~50자") String displayName,
        @NotNull @Pattern(regexp = "INVESTIGATOR|APPROVER|ADMIN", message = "INVESTIGATOR·APPROVER·ADMIN 중 하나") String role,
        @NotNull @Size(min = 8, max = 128, message = "8~128자") @BcryptPassword String initialPassword) {

    public CreateUserRequest {
        displayName = displayName == null ? null : displayName.strip();
    }

    @Override
    public String toString() {
        return "CreateUserRequest[loginId=" + loginId + ", displayName=" + displayName + ", role=" + role + "]";
    }
}
