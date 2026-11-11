package com.team.aml.user.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /admin/users/{id}/reset-password 본문 (SCRUM-135 ②). toString 에 비밀번호를 넣지 않는다. */
public record ResetPasswordRequest(
        @NotNull @Size(min = 8, max = 128, message = "8~128자") @BcryptPassword String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[newPassword=***]";
    }
}
