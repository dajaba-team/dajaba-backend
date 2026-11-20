package com.team.aml.cases.api;

import com.team.aml.cases.application.AskCaseQuestion;
import com.team.aml.cases.application.ListCaseQuestions;
import com.team.aml.shared.config.AmlProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 질문 답변이 꺼져 있으면(aml.ai.ask-enabled=false, 기본) A8·C9 는 없는 주소와 같은 404 — 유스케이스를 부르지 않는다
 * (설계 문서 11.3, SCRUM-302). NoResourceFoundException → 404 NOT_FOUND 변환은 GlobalExceptionHandler 몫(SCRUM-157).
 */
class CaseQuestionControllerTest {

    private final AskCaseQuestion ask = mock(AskCaseQuestion.class);
    private final ListCaseQuestions list = mock(ListCaseQuestions.class);
    private final CaseQuestionController off = new CaseQuestionController(ask, list, properties(false));

    @Test
    void 꺼져_있으면_질문하기는_404이고_AI도_DB도_건드리지_않는다() {
        assertThatThrownBy(() -> off.ask("CASE-2022-000057", new AskQuestionRequest("몇 건이야?")))
                .isInstanceOf(NoResourceFoundException.class);
        verifyNoInteractions(ask);
    }

    @Test
    void 꺼져_있으면_지난_질문도_404() {
        assertThatThrownBy(() -> off.list("CASE-2022-000057", new CaseQuestionQuery(null, null)))
                .isInstanceOf(NoResourceFoundException.class);
        verifyNoInteractions(list);
    }

    private static AmlProperties properties(boolean askEnabled) {
        return new AmlProperties(new AmlProperties.Ai("http://ai", "key", 90, 500, false, 30, askEnabled, 5),
                null, null, null);
    }
}
