package com.team.aml.source.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * transactions 테이블. 적재는 COPY로 하고 JPA로는 읽기만 한다.
 * 정답 라벨 is_laundering, pattern_type, pattern_id는 매핑하지 않는다.
 */
@Entity
@Immutable
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(nullable = false)
    private LocalDateTime ts;

    @Column(name = "from_bank_id", nullable = false, length = 16)
    private String fromBankId;

    @Column(name = "from_bank_id_raw", nullable = false, length = 16)
    private String fromBankIdRaw;

    @Column(name = "from_account_no", nullable = false, length = 32)
    private String fromAccountNo;

    @Column(name = "from_account_key", nullable = false, length = 64)
    private String fromAccountKey;

    @Column(name = "to_bank_id", nullable = false, length = 16)
    private String toBankId;

    @Column(name = "to_bank_id_raw", nullable = false, length = 16)
    private String toBankIdRaw;

    @Column(name = "to_account_no", nullable = false, length = 32)
    private String toAccountNo;

    @Column(name = "to_account_key", nullable = false, length = 64)
    private String toAccountKey;

    @Column(name = "amount_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountReceived;

    @Column(name = "currency_received", nullable = false, length = 32)
    private String currencyReceived;

    @Column(name = "amount_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountPaid;

    @Column(name = "currency_paid", nullable = false, length = 32)
    private String currencyPaid;

    @Column(name = "payment_format", nullable = false, length = 32)
    private String paymentFormat;

    @Column(name = "is_self_transfer", nullable = false)
    private boolean selfTransfer;

    @Column(name = "source_file_id", nullable = false)
    private Long sourceFileId;

    @Column(name = "source_row_number", nullable = false)
    private Long sourceRowNumber;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {}

    public Long getTransactionId() { return transactionId; }
    public LocalDateTime getTs() { return ts; }
    public String getFromBankId() { return fromBankId; }
    public String getFromBankIdRaw() { return fromBankIdRaw; }
    public String getFromAccountNo() { return fromAccountNo; }
    public String getFromAccountKey() { return fromAccountKey; }
    public String getToBankId() { return toBankId; }
    public String getToBankIdRaw() { return toBankIdRaw; }
    public String getToAccountNo() { return toAccountNo; }
    public String getToAccountKey() { return toAccountKey; }
    public BigDecimal getAmountReceived() { return amountReceived; }
    public String getCurrencyReceived() { return currencyReceived; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public String getCurrencyPaid() { return currencyPaid; }
    public String getPaymentFormat() { return paymentFormat; }
    public boolean isSelfTransfer() { return selfTransfer; }
    public Long getSourceFileId() { return sourceFileId; }
    public Long getSourceRowNumber() { return sourceRowNumber; }
    public Instant getCreatedAt() { return createdAt; }
}
