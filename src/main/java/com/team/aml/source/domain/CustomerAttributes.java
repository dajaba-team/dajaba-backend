package com.team.aml.source.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/** customer_attributes 테이블. 읽기 전용이며 값 생성 범위는 서식 5-7 결정을 따른다. */
@Entity
@Immutable
@Table(name = "customer_attributes")
public class CustomerAttributes {

    @Id
    @Column(name = "entity_id", length = 32)
    private String entityId;

    @Column(nullable = false, length = 32)
    private String industry;

    @Column(name = "risk_level", nullable = false, length = 8)
    private String riskLevel;

    @Column(name = "account_purpose", nullable = false, length = 32)
    private String accountPurpose;

    @Column(name = "onboarded_at", nullable = false)
    private LocalDate onboardedAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected CustomerAttributes() {}

    public String getEntityId() { return entityId; }
    public String getIndustry() { return industry; }
    public String getRiskLevel() { return riskLevel; }
    public String getAccountPurpose() { return accountPurpose; }
    public LocalDate getOnboardedAt() { return onboardedAt; }
    public String getCountryCode() { return countryCode; }
    public Instant getCreatedAt() { return createdAt; }
}
