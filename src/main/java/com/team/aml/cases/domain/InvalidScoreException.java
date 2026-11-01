package com.team.aml.cases.domain;

import com.team.aml.shared.domain.DomainException;

import java.util.HashMap;
import java.util.Map;

/**
 * 점수가 없거나 0~1 밖. LOW 로 대신하지 않는다. 생성 배치는 이 경보를 건너뛰고 사유를 남긴다.
 * 화면 API 로는 나가지 않는 내부용이다 (03_shared.md §2 표 등록은 배치 구현 때 같이).
 */
public class InvalidScoreException extends DomainException {
    public InvalidScoreException(Object score) {
        super("등급을 계산할 수 없는 점수입니다: " + score, details(score));
    }

    private static Map<String, Object> details(Object score) {
        Map<String, Object> details = new HashMap<>();
        if (score != null) {
            details.put("score", score);
        }
        return Map.copyOf(details);
    }
}
