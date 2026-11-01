package com.team.aml.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** swagger-ui: http://localhost:8080/swagger-ui.html (local 만. prod 는 꺼짐). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("xsrf", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN")
                        .description("쓰기 요청(POST/PUT/PATCH/DELETE)에 필요. 브라우저 쿠키 XSRF-TOKEN 값을 붙여 넣는다. login 은 예외")))
                .addSecurityItem(new SecurityRequirement().addList("xsrf"))
                .info(new Info()
                .title("AML / STR API")
                .version("v1")
                .description("""
                        세션 쿠키 인증. POST /api/v1/auth/login {"login_id": "inv01", "password": DEV_USER_PASSWORD} 로 로그인한 뒤
                        GET /api/v1/auth/me 가 200 이면 준비 끝. 쓰기 요청은 X-XSRF-TOKEN 헤더 필요(XSRF-TOKEN 쿠키 값).
                        """));
    }
}
