package com.team.aml.source.application;

import com.team.aml.source.domain.CustomerAttributesFileFormatException;
import com.team.aml.source.domain.CustomerAttributesLoadRepository;
import com.team.aml.source.domain.CustomerAttributesLoadResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 가상 KYC 파일(customer_attributes.csv, 팀이 만든 HI-Small_STR_expanded)을 customer_attributes로 적재한다 (SCRUM-430).
 * ImportIbmDataset 트랜잭션에 참여하고, 계좌 적재(법인 INSERT) 뒤에 부른다 — customer_attributes.entity_id가 법인 FK라서.
 * 파일은 선택이다 — 없으면 건너뛴다(SKIPPED). 계좌 개설 목적 빈칸은 NULL(V020).
 */
@Service
public class LoadCustomerAttributesFile {

    static final String HEADER = "entity_id,industry,risk_level,account_purpose,onboarded_at,country_code";

    private final CustomerAttributesLoadRepository customerAttributes;

    public LoadCustomerAttributesFile(CustomerAttributesLoadRepository customerAttributes) {
        this.customerAttributes = customerAttributes;
    }

    @Transactional
    public CustomerAttributesLoadResult execute(Path file) {
        if (!Files.isRegularFile(file)) {
            return CustomerAttributesLoadResult.SKIPPED;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(stripBom(header).strip())) {
                throw new CustomerAttributesFileFormatException("헤더가 다릅니다");
            }
            return customerAttributes.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("KYC 파일을 읽을 수 없습니다", e);
        }
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
