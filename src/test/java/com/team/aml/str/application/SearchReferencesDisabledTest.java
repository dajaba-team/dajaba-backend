package com.team.aml.str.application;

import com.team.aml.cases.domain.CaseRepository;
import com.team.aml.shared.ai.AiClient;
import com.team.aml.shared.config.AmlProperties;
import com.team.aml.shared.security.CurrentUser;
import com.team.aml.str.domain.ReferenceSearchDisabledException;
import com.team.aml.str.domain.StrDraftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 참고 사례 찾기가 꺼져 있을 때 (SCRUM-354, 설계 문서 A 13-4) — DB 없이. 403 FORBIDDEN, 초안·AI 를 건드리지 않는다. */
class SearchReferencesDisabledTest {

    private final StrDraftRepository drafts = mock(StrDraftRepository.class);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AiClient aiClient = mock(AiClient.class);
    private final AmlProperties properties = mock(AmlProperties.class, RETURNS_DEEP_STUBS);

    @Test
    void 실패_설정이_꺼져_있으면_403_FORBIDDEN이고_초안도_AI도_건드리지_않는다() {
        when(properties.ai().referenceSearchEnabled()).thenReturn(false);
        SearchReferences sut = new SearchReferences(drafts, cases, currentUser, aiClient, properties,
                mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> sut.execute("DRF-000001", List.of("FAN_IN"), "검색어"))
                .isInstanceOf(ReferenceSearchDisabledException.class)
                .satisfies(e -> assertThat(((ReferenceSearchDisabledException) e).code()).isEqualTo("FORBIDDEN"));
        verifyNoInteractions(drafts, cases, currentUser, aiClient);
    }
}
