package com.team.aml.user.api;

import com.team.aml.shared.security.AppUserDetails;
import com.team.aml.user.application.GetMe;
import com.team.aml.user.domain.User;
import com.team.aml.user.domain.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [W1 목업] local 프로파일에서만. 비밀번호 없이 login_id 만으로 진짜 세션을 만든다.
 * 세션 쿠키·CSRF·만료·maximumSessions·/me·/logout 은 전부 진짜 경로를 탄다 — 가짜인 건 "비밀번호를 안 본다" 하나.
 * W2 에 POST /api/v1/auth/login(BCrypt 검사 + access_logs LOGIN_OK/FAIL)이 들어오면 이 클래스는 지운다.
 *
 * swagger 에서: POST /api/v1/auth/mock-login?login_id=inv01 → 200, 그 다음 GET /api/v1/auth/me → 200 이면 뼈대 완성.
 */
@Tag(name = "auth")
@Profile("local")
@RestController
@RequestMapping("/api/v1/auth")
public class MockLoginController {

    private final UserRepository users;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public MockLoginController(UserRepository users) {
        this.users = users;
    }

    @Operation(summary = "[local 전용 목업] 비밀번호 없이 로그인", description = "admin / inv01 / apr01 / inv02 (DevUserSeeder). W2 에 진짜 login 으로 교체")
    @PostMapping("/mock-login")
    public GetMe.Result mockLogin(@RequestParam("login_id") String loginId, HttpServletRequest req, HttpServletResponse res) {
        User u = users.findByLoginId(loginId)
                .filter(User::canLogin)
                .orElseThrow(() -> new MockLoginFailedException(loginId));

        AppUserDetails principal = new AppUserDetails(u.getId(), u.getLoginId(), u.getDisplayName(), u.getRole().name());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);   // 여기서 SESSION 쿠키가 생긴다

        return new GetMe.Result(u.getId(), u.getLoginId(), u.getDisplayName(), u.getRole().name());
    }
}
