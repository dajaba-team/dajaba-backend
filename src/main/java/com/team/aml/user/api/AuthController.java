package com.team.aml.user.api;

import com.team.aml.user.application.GetMe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/auth — me 만 여기. logout 은 SecurityConfig 의 logout 설정이 처리(POST /api/v1/auth/logout → 204).
 * 진짜 login(POST /api/v1/auth/login, BCrypt 검사)은 W2 에 추가. 그때까지 local 은 MockLoginController.
 */
@Tag(name = "auth")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final GetMe getMe;

    public AuthController(GetMe getMe) {
        this.getMe = getMe;
    }

    @Operation(summary = "현재 사용자", description = "세션이 없으면 401 UNAUTHENTICATED")
    @GetMapping("/me")
    public GetMe.Result me() {
        return getMe.execute();
    }
}
