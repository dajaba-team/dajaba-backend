package com.team.aml.cases.api;

import com.team.aml.cases.application.CaseMetrics;
import com.team.aml.cases.domain.Case;
import com.team.aml.cases.domain.CaseStatus;
import com.team.aml.cases.domain.InvalidCaseTransitionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** cases 엔드포인트의 409 CONCURRENT_UPDATE 만 aml.cases.concurrent.updates{action} 로 센다 (SCRUM-276). */
class CaseConflictCounterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CaseConflictCounter counter = new CaseConflictCounter(new CaseMetrics(registry));
    private final CaseController controller = mock(CaseController.class);

    private double count(String action) {
        Counter c = registry.find(CaseMetrics.CONCURRENT_UPDATES).tag("action", action).counter();
        return c == null ? 0 : c.count();
    }

    private double total() {
        return registry.find(CaseMetrics.CONCURRENT_UPDATES).counters().stream().mapToDouble(Counter::count).sum();
    }

    private Object resolve(Object handler, Exception ex) {
        return counter.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), handler, ex);
    }

    private HandlerMethod handler(String methodName) {
        Method m = Arrays.stream(CaseController.class.getMethods()).filter(x -> x.getName().equals(methodName))
                .findFirst().orElseThrow();
        return new HandlerMethod(controller, m);
    }

    // --- 정상 ---------------------------------------------------------------

    @Test
    void 정상_유스케이스_version_검사와_커밋_때_충돌_둘_다_action별로_센다() {
        resolve(handler("updateNotes"), new OptimisticLockException("version"));
        resolve(handler("updateNotes"), new ObjectOptimisticLockingFailureException(Case.class, 1L));
        resolve(handler("assign"), new ObjectOptimisticLockingFailureException(Case.class, 1L));

        assertThat(count("UPDATE_NOTES")).isEqualTo(2);
        assertThat(count("ASSIGN")).isEqualTo(1);
    }

    @Test
    void 정상_늘_null을_돌려_응답은_GlobalExceptionHandler가_만든다() {
        assertThat(resolve(handler("close"), new OptimisticLockException("v"))).isNull();
        assertThat(resolve(handler("close"), new IllegalStateException())).isNull();
        assertThat(counter.getOrder()).isLessThan(0);   // ExceptionHandlerExceptionResolver(0)보다 먼저 본다
    }

    @Test
    void 정상_CaseController의_변경_요청은_모두_고정_action이_있다() {   // 메서드 이름을 바꾸면 여기서 잡힌다
        List<String> writes = Arrays.stream(CaseController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class))
                .map(Method::getName).toList();

        assertThat(writes).isNotEmpty();
        assertThat(CaseConflictCounter.ACTIONS.keySet()).containsExactlyInAnyOrderElementsOf(writes);
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    void 경계_다른_409는_세지_않는다() {
        resolve(handler("escalate"), InvalidCaseTransitionException.notEditable(CaseStatus.CLOSED, null, "ESCALATE"));

        assertThat(total()).isZero();
    }

    @Test
    void 경계_감싼_충돌은_500으로_나가므로_세지_않는다() {
        resolve(handler("assign"), new IllegalStateException("wrapped", new OptimisticLockException("v")));

        assertThat(total()).isZero();
    }

    @Test
    void 경계_cases_컨트롤러가_아니면_세지_않는다() throws Exception {
        Object other = new Object();
        resolve(new HandlerMethod(other, Object.class.getMethod("toString")), new OptimisticLockException("v"));
        resolve(null, new OptimisticLockException("v"));
        resolve("notAHandlerMethod", new OptimisticLockException("v"));

        assertThat(total()).isZero();
    }

    @Test
    void 경계_목록에_없는_메서드는_OTHER() {
        resolve(handler("detail"), new OptimisticLockException("v"));

        assertThat(count("OTHER")).isEqualTo(1);
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    void 실패_지표가_고장나도_예외를_던지지_않고_null() {
        CaseConflictCounter broken = new CaseConflictCounter(new CaseMetrics(new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry down");
            }
        }));

        assertThat(broken.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("assign"), new OptimisticLockException("v"))).isNull();
    }
}
