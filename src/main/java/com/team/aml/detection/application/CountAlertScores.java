package com.team.aml.detection.application;

import com.team.aml.detection.domain.AlertReviewRepository;
import com.team.aml.detection.domain.AlertScoreStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 경보 점수 집계 (SCRUM-360) — 관리자 대시보드의 위험 등급 분포. 등급 경계값은 cases 의 운영 정책이라
 * 여기서는 숫자로만 받는다(detection 은 cases 를 모름). 둘 다 null 이면 등급은 나누지 않는다.
 * date 가 null 이면 전체 기간.
 */
@Service
public class CountAlertScores {

    private final AlertReviewRepository reviews;

    public CountAlertScores(AlertReviewRepository reviews) {
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public AlertScoreStats count(LocalDate date, BigDecimal lowToMedium, BigDecimal mediumToHigh) {
        return reviews.scoreStats(date, lowToMedium, mediumToHigh);
    }
}
