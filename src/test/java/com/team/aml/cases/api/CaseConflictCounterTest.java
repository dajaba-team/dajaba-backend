package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseMetrics;
import com.team.aml.cases.domain.CaseAction;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 케이스 변경 API 의 CONCURRENT_UPDATE 건수 (SCRUM-276). 실제 409 흐름은 CaseApiTest 가 HTTP 로 본다.
 */
class CaseConflictCounterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaseConflictCounter counter = new CaseConflictCounter(new CaseMetrics(registry));
    private final CaseController controller = mock(CaseController.class);

    private double conflicts(String action) {
        Counter c = registry.find(CaseMetrics.CONFLICTS).tag("action", action).counter();
        return c == null ? 0 : c.count();
    }

    private void finish(String method, Exception handled) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (handled != null) {
            req.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, handled);   // GlobalExceptionHandler 가 처리한 예외
        }
        Method m = Arrays.stream(CaseController.class.getMethods()).filter(x -> x.getName().equals(method))
                .findFirst().orElseThrow();
        counter.afterCompletion(req, new MockHttpServletResponse(), new HandlerMethod(controller, m), null);
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_화면_version이_달라_막힌_변경은_동작별로_센다() throws Exception {
        finish("escalate", new OptimisticLockException("stale"));
        finish("updateNotes", new OptimisticLockException("stale"));

        assertThat(conflicts("ESCALATE")).isEqualTo(1);
        assertThat(conflicts("UPDATE_NOTES")).isEqualTo(1);
    }

    @Test
    void 정상_커밋_때_Version_충돌도_센다() throws Exception {
        finish("assign", new ObjectOptimisticLockingFailureException(Object.class, 1L));

        assertThat(conflicts("ASSIGN")).isEqualTo(1);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_다른_409와_성공은_안_센다() throws Exception {
        finish("close", InvalidCaseTransitionException.notEditable(CaseStatus.CLOSED, null, "CLOSE"));
        finish("close", null);

        assertThat(registry.find(CaseMetrics.CONFLICTS).counters()).isEmpty();
    }

    @Test
    void 경계_조회_API는_표시가_없어_안_센다() throws Exception {
        finish("detail", new OptimisticLockException("stale"));

        assertThat(registry.find(CaseMetrics.CONFLICTS).counters()).isEmpty();
    }

    @Test
    void 경계_cause에_있는_충돌도_GlobalExceptionHandler처럼_본다() {
        assertThat(CaseConflictCounter.isConcurrentUpdate(new IllegalStateException(new OptimisticLockException("x"))))
                .isTrue();
        assertThat(CaseConflictCounter.isConcurrentUpdate(new IllegalStateException("x"))).isFalse();
        assertThat(CaseConflictCounter.isConcurrentUpdate(null)).isFalse();
    }

    // --- 빠짐 방지 ----------------------------------------------------------

    @Test
    void 실패_방지_케이스_변경_API는_전부_표시가_있다() {
        // version 을 받는 변경 API(POST·PUT)를 새로 만들고 @CountsConflicts 를 깜박하면 여기서 깨진다
        List<Method> changes = Arrays.stream(CaseController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class))
                .toList();

        assertThat(changes).isNotEmpty().allSatisfy(m -> assertThat(m.getAnnotation(CountsConflicts.class))
                .as(m.getName()).isNotNull());
        assertThat(changes).extracting(m -> m.getAnnotation(CountsConflicts.class).value())
                .containsExactlyInAnyOrder(CaseAction.ASSIGN, CaseAction.REASSIGN, CaseAction.ESCALATE, CaseAction.CLOSE,
                        CaseAction.REFRESH_SUMMARY, CaseAction.UPDATE_NOTES);
    }
}
