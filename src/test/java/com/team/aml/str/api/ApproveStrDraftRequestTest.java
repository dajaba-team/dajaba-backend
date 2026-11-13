package com.team.aml.str.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 승인 본문 (#106 리뷰 2번).
 * 400 경로는 SubmitStrDraftRequestTest 처럼 검사기로 — 본점 의심 정도 1~5 필수·기본값 없음, case_version 필수 (계약서 5.5).
 * 키 이름은 CreateStrDraftRequestJsonTest 처럼 앱과 같은 Jackson 설정(SNAKE_CASE)으로 — #83 같은 camelCase 회귀 막기.
 */
@JsonTest
class ApproveStrDraftRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Autowired
    ObjectMapper om;

    private static Set<String> violations(Long caseVersion, Integer levelHq) {
        return VALIDATOR.validate(new ApproveStrDraftRequest(caseVersion, levelHq)).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    // 1
    @Test
    void 정상_본점_정도_1부터_5까지와_case_version_0은_통과() {
        assertThat(violations(3L, 1)).isEmpty();
        assertThat(violations(3L, 5)).isEmpty();
        assertThat(violations(0L, 3)).isEmpty();             // 버전 0 도 정상 값
    }

    // 2
    @Test
    void 실패_본점_정도가_없거나_0이거나_6이면_위반() {   // 기본값 없음 — 비워 두면 400 details.suspicion_level_hq
        assertThat(violations(3L, null)).containsExactly("suspicionLevelHq");
        assertThat(violations(3L, 0)).containsExactly("suspicionLevelHq");
        assertThat(violations(3L, 6)).containsExactly("suspicionLevelHq");
    }

    // 3
    @Test
    void 실패_case_version이_없거나_음수면_위반() {
        assertThat(violations(null, 3)).containsExactly("caseVersion");
        assertThat(violations(-1L, 3)).containsExactly("caseVersion");
    }

    // 4
    @Test
    void 정상_계약서대로_snake_case_키를_읽는다() throws Exception {
        ApproveStrDraftRequest r = om.readValue("{\"case_version\": 7, \"suspicion_level_hq\": 4}",
                ApproveStrDraftRequest.class);

        assertThat(r.caseVersion()).isEqualTo(7L);
        assertThat(r.suspicionLevelHq()).isEqualTo(4);
    }

    // 5
    @Test
    void 실패_camelCase_키는_읽지_않아서_400으로_이어진다() throws Exception {
        ApproveStrDraftRequest r = om.readValue("{\"caseVersion\": 7, \"suspicionLevelHq\": 4}",
                ApproveStrDraftRequest.class);

        assertThat(r.caseVersion()).isNull();
        assertThat(r.suspicionLevelHq()).isNull();
        assertThat(violations(r.caseVersion(), r.suspicionLevelHq()))
                .containsExactlyInAnyOrder("caseVersion", "suspicionLevelHq");
    }
}
