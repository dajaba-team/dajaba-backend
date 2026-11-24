package com.team.aml.detection.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 경보 근거 digest (SCRUM-415, 규약 6절 근거 신원 칸 — 정본은 AI `docs/scoring/01` 「근거 신원 칸」).
 * 근거마다 `원본파일명:원본행번호` → (파일명 문자열, 행번호 정수) 오름차순 → `\n` 으로 이음(끝 줄바꿈 없음)
 * → UTF-8 SHA-256 → 소문자 16진 64자. 근거가 없으면 빈 문자열의 값.
 */
public final class EvidenceDigest {

    private EvidenceDigest() {}

    /** 입력 목록은 바꾸지 않는다. null 목록·null 원소는 NPE. */
    public static String of(List<EvidenceSource> evidence) {
        List<EvidenceSource> sorted = new ArrayList<>(evidence);
        sorted.sort(Comparator.comparing(EvidenceSource::fileName).thenComparingLong(EvidenceSource::sourceRowNumber));
        String lines = sorted.stream()
                .map(source -> source.fileName() + ":" + source.sourceRowNumber())
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(lines.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }
}
