package com.team.aml.source.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** accounts 테이블. 적재 후 읽기 전용이며 법인은 ID로만 참조한다. */
@Entity
@Immutable
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_key", length = 64)
    private String accountKey;

    @Column(name = "bank_id", nullable = false, length = 16)
    private String bankId;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "account_no", nullable = false, length = 32)
    private String accountNo;

    @Column(name = "entity_id", nullable = false, length = 32)
    private String entityId;

    @Column(name = "bank_country", length = 64)
    private String bankCountry;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    public String getAccountKey() { return accountKey; }
    public String getBankId() { return bankId; }
    public String getBankName() { return bankName; }
    public String getAccountNo() { return accountNo; }
    public String getEntityId() { return entityId; }
    public String getBankCountry() { return bankCountry; }
    public Instant getCreatedAt() { return createdAt; }
}
