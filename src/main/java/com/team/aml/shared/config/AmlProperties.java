package com.team.aml.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml 의 aml.* 를 타입으로. 비밀값은 환경변수에서 온다. */
@ConfigurationProperties(prefix = "aml")
public record AmlProperties(Ai ai, DevUsers devUsers, Cors cors, String datasetDir) {

    public record Ai(String baseUrl, String internalKey, int timeoutSeconds, int maxBatch, boolean mock) {}

    public record DevUsers(boolean enabled, String password) {}

    public record Cors(String allowedOrigin) {}
}
