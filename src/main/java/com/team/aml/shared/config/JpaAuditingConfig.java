package com.team.aml.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** BaseEntity 의 created_* 와 updated_* 를 자동으로 채운다. 누가(auditor)는 SessionAuditorAware. */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "sessionAuditorAware")
public class JpaAuditingConfig {
}
