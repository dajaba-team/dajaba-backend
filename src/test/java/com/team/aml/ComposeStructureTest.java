package com.team.aml;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-263 — 운영 도구 compose 뼈대의 구조 규칙. Docker 없이 `compose.yaml`을 YAML로 읽어서 본다.
 * <ul>
 *   <li>운영 도구는 전부 {@code profiles: [ops]} — 평소 {@code docker compose up}은 db·app만 (지금과 같음)</li>
 *   <li>운영 도구는 호스트 포트 없음(로컬 확인은 README의 {@code compose.override.yml}), 태그·digest 고정, healthcheck, 메모리 상한</li>
 *   <li>app {@code depends_on}은 db뿐 — 도구가 죽어도 로그인·케이스·STR은 그대로</li>
 *   <li>app {@code environment}에 {@code MANAGEMENT_SERVER_ADDRESS: 0.0.0.0} — 관리 포트 8081 은 기본 127.0.0.1 이라
 *       컨테이너에선 이게 없으면 Prometheus(app:8081)가 못 들어와 DOWN (SCRUM-271 후속, 03_shared §13)</li>
 *   <li>app 은 관리 포트 8081 을 호스트로 내보내지 않음 — {@code ports} 의 컨테이너 쪽 포트에 8081 없음(짧은·긴 문법, 범위 포함),
 *       {@code network_mode} 키 없음(값이 보간이어도 막게 키로 본다). 컨테이너 안 주소가 0.0.0.0 이라 노출 여부는 이 둘이 정한다</li>
 *   <li>app 은 {@code extends} 를 쓰지 않음(이 검사는 상속 풀기 전 정의만 읽음), 목록 모양 environment 에서
 *       {@code MANAGEMENT_SERVER_ADDRESS} 는 값과 함께 한 번만</li>
 *   <li>비밀값은 {@code ${VAR:?}}를 쓰지 않음(프로필 밖에서도 보간 오류) → {@code ${VAR:-}} + {@code ops-env-check}가 비면 막음</li>
 * </ul>
 * db·app 정의가 develop과 같은지는 이 테스트가 아니라 PR diff로 본다.
 */
class ComposeStructureTest {

    static final Set<String> OPS_SERVICES = Set.of(
            "ops-env-check",
            "prometheus", "grafana", "loki", "alloy",
            "redis", "rabbitmq", "minio", "minio-init", "elasticsearch",
            "clickhouse", "langfuse-db-init", "langfuse-worker", "langfuse-web");

    /** 1회 실행 후 종료(exit 0)하는 컨테이너 — 떠 있는 상태가 없어 healthcheck 대상이 아님. */
    static final Set<String> ONE_SHOT = Set.of("ops-env-check", "minio-init", "langfuse-db-init");

    /**
     * healthcheck 예외(1회 실행 제외) — 11/18 결정.
     * loki: 공식 이미지가 distroless라 셸·wget·curl이 없어 컨테이너 안에서 검사할 방법이 없음.
     * 대신 Grafana 데이터 소스와 ★L 확인(`/ready`)으로 본다.
     */
    static final Set<String> HEALTHCHECK_EXEMPT = Set.of("loki");

    /** {@code .env} 비밀값을 받는 서비스 — {@code ops-env-check}가 정상 종료한 뒤에만 뜬다. */
    static final Set<String> USES_SECRETS = Set.of(
            "grafana", "redis", "rabbitmq", "minio", "minio-init", "elasticsearch",
            "clickhouse", "langfuse-db-init", "langfuse-worker", "langfuse-web");

    /** 데이터를 들고 있어 이름 있는 볼륨이 있어야 하는 서비스. */
    static final Set<String> STATEFUL = Set.of(
            "prometheus", "grafana", "loki", "redis", "rabbitmq", "minio", "elasticsearch", "clickhouse");

    private static final Path COMPOSE = Path.of("compose.yaml");
    private static String composeText;
    private static Map<String, Object> compose;

    @BeforeAll
    static void load() throws Exception {
        composeText = Files.readString(COMPOSE);
        compose = new Yaml().load(composeText);
    }

    // ---- 정상: 실제 compose.yaml ----

    @Test
    void 운영_도구_서비스가_전부_있다() {
        assertThat(services(compose).keySet()).containsAll(OPS_SERVICES);
    }

    @Test
    void 실제_compose가_규칙을_모두_지킨다() {
        assertThat(violations(composeText, compose)).isEmpty();
    }

    @Test
    void app의_depends_on은_db뿐이다() {
        Map<String, Object> app = service(compose, "app");
        assertThat(dependsOn(app)).containsExactly("db");
    }

    @Test
    void db와_app에는_profiles가_없다() {
        assertThat(service(compose, "db")).doesNotContainKey("profiles");
        assertThat(service(compose, "app")).doesNotContainKey("profiles");
    }

    @Test
    void 프로필_없이_뜨는_서비스는_db와_app뿐이다() {
        Set<String> withoutProfile = new TreeSet<>();
        services(compose).forEach((name, def) -> {
            if (!asMap(def).containsKey("profiles")) withoutProfile.add(name);
        });
        assertThat(withoutProfile).containsExactly("app", "db");
    }

    // ---- 경계·실패: 규칙을 어긴 가짜 compose를 검사기가 잡는지 ----

    @Test
    void 규칙을_지킨_최소_compose는_위반이_없다() {
        assertThat(violationsOf(baseCompose(""))).isEmpty();
    }

    @Test
    void ops_서비스에_ports가_있으면_잡는다() {
        String yaml = baseCompose("").replace(
                "  prometheus:\n", "  prometheus:\n    ports: [\"9090:9090\"]\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("prometheus") && v.contains("ports"));
    }

    @Test
    void ops_서비스에_profiles가_없으면_잡는다() {
        String yaml = baseCompose("").replace(
                "  prometheus:\n    profiles: [ops]\n", "  prometheus:\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("prometheus") && v.contains("profiles"));
    }

    @Test
    void latest_태그와_태그_없는_이미지를_잡는다() {
        String latest = baseCompose("").replace("prom/prometheus:v3.16.0", "prom/prometheus:latest");
        String untagged = baseCompose("").replace("prom/prometheus:v3.16.0", "prom/prometheus");
        assertThat(violationsOf(latest)).anyMatch(v -> v.contains("prometheus") && v.contains("image"));
        assertThat(violationsOf(untagged)).anyMatch(v -> v.contains("prometheus") && v.contains("image"));
    }

    @Test
    void chainguard_이미지는_digest가_아니면_잡는다() {
        String yaml = baseCompose("").replace(
                "cgr.dev/chainguard/minio@sha256:" + "a".repeat(64), "cgr.dev/chainguard/minio:latest");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("minio") && v.contains("image"));
    }

    @Test
    void healthcheck이_없으면_잡고_예외는_통과한다() {
        String yaml = baseCompose("").replace(
                "    healthcheck: {test: [\"CMD\", \"true\"]}\n    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n",
                "    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("prometheus") && v.contains("healthcheck"));
        // loki·1회 실행 컨테이너는 baseCompose에서 healthcheck이 없는데도 위반이 아님
        assertThat(violationsOf(baseCompose(""))).noneMatch(v -> v.contains("healthcheck"));
    }

    @Test
    void 메모리_상한이_없으면_잡고_deploy_limits도_인정한다() {
        String none = baseCompose("").replace(
                "    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n",
                "    volumes: [\"prometheus-data:/prometheus\"]\n");
        String deploy = baseCompose("").replace(
                "    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n",
                "    deploy: {resources: {limits: {memory: 256m}}}\n    volumes: [\"prometheus-data:/prometheus\"]\n");
        assertThat(violationsOf(none)).anyMatch(v -> v.contains("prometheus") && v.contains("메모리"));
        assertThat(violationsOf(deploy)).isEmpty();
    }

    @Test
    void 필수값_보간_문법을_쓰면_잡는다() {
        String yaml = baseCompose("").replace("${GRAFANA_ADMIN_PASSWORD:-}", "${GRAFANA_ADMIN_PASSWORD:?빠짐}");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains(":?"));
    }

    @Test
    void 비밀값_서비스가_ops_env_check을_기다리지_않으면_잡는다() {
        String yaml = baseCompose("").replace(
                "  grafana:\n    profiles: [ops]\n    depends_on: {ops-env-check: {condition: service_completed_successfully}}\n",
                "  grafana:\n    profiles: [ops]\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("grafana") && v.contains("ops-env-check"));
    }

    @Test
    void app이_도구에_의존하면_잡는다() {
        String yaml = baseCompose("").replace(
                "    depends_on: {db: {condition: service_healthy}}\n",
                "    depends_on: {db: {condition: service_healthy}, redis: {condition: service_healthy}}\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("depends_on"));
    }

    @Test
    void app에_관리_포트_주소가_없거나_0000이_아니면_잡는다() {
        String missing = baseCompose("").replace(
                "{X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}", "{X: y}");
        String loopback = baseCompose("").replace(
                "MANAGEMENT_SERVER_ADDRESS: 0.0.0.0", "MANAGEMENT_SERVER_ADDRESS: 127.0.0.1");
        assertThat(violationsOf(missing)).anyMatch(v -> v.contains("app") && v.contains("MANAGEMENT_SERVER_ADDRESS"));
        assertThat(violationsOf(loopback)).anyMatch(v -> v.contains("app") && v.contains("MANAGEMENT_SERVER_ADDRESS"));
    }

    @Test
    void app_environment가_목록_모양이어도_관리_포트_주소를_읽는다() {
        String list = baseCompose("").replace(
                "{X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}", "[\"X=y\", \"MANAGEMENT_SERVER_ADDRESS=0.0.0.0\"]");
        assertThat(violationsOf(list)).isEmpty();
    }

    /** 최소 compose 의 app 에 ports 줄 하나를 넣는다. */
    private static String appWithPorts(String ports) {
        return baseCompose("").replace("  app:\n    build: .\n", "  app:\n    build: .\n    ports: " + ports + "\n");
    }

    @Test
    void app_ports에_컨테이너_쪽_8081이_있으면_모양과_상관없이_잡는다() {
        for (String ports : List.of(
                "[\"8081:8081\"]",
                "[\"127.0.0.1:8081:8081\"]",
                "[\"8081\"]",
                "[8081]",
                "[\"18081:8081/tcp\"]",
                "[\"8080-8081:8080-8081\"]",
                "[{target: 8081, published: \"8081\"}]",
                "[{target: 8081, host_ip: 127.0.0.1, published: 18081, protocol: tcp}]")) {
            assertThat(violationsOf(appWithPorts(ports)))
                    .as(ports)
                    .anyMatch(v -> v.contains("app") && v.contains("8081"));
        }
    }

    @Test
    void app_ports가_8080뿐이면_통과하고_호스트_쪽_8081은_관리_포트가_아니다() {
        for (String ports : List.of(
                "[\"8080:8080\"]",
                "[\"127.0.0.1:8080:8080\"]",
                "[\"8081:8080\"]",
                "[{target: 8080, published: 8081}]")) {
            assertThat(violationsOf(appWithPorts(ports))).as(ports).isEmpty();
        }
    }

    @Test
    void app_network_mode는_보간이어도_잡는다() {
        String yaml = baseCompose("").replace("  app:\n    build: .\n",
                "  app:\n    build: .\n    network_mode: \"${APP_NETWORK_MODE:-host}\"\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("network_mode"));
    }

    @Test
    void app_network_mode는_bridge여도_키가_있으면_잡는다() {
        String yaml = baseCompose("").replace("  app:\n    build: .\n", "  app:\n    build: .\n    network_mode: bridge\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("network_mode"));
    }

    @Test
    void app_network_mode가_host면_잡는다() {
        String yaml = baseCompose("").replace("  app:\n    build: .\n", "  app:\n    build: .\n    network_mode: host\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("network_mode"));
    }

    /** 최소 compose 에 부모 서비스 하나를 두고 app 이 그걸 extends 하게. */
    private static String appExtending(String parentBody) {
        return baseCompose("  inherited:\n    build: .\n" + parentBody)
                .replace("  app:\n    build: .\n", "  app:\n    build: .\n    extends: {service: inherited}\n");
    }

    @Test
    void app이_extends를_쓰면_잡는다_부모의_ports_8081() {
        assertThat(violationsOf(appExtending("    ports: [\"18081:8081\"]\n")))
                .anyMatch(v -> v.contains("app") && v.contains("extends"));
    }

    @Test
    void app이_extends를_쓰면_잡는다_부모의_network_mode_host() {
        assertThat(violationsOf(appExtending("    network_mode: host\n")))
                .anyMatch(v -> v.contains("app") && v.contains("extends"));
    }

    @Test
    void 목록_env에서_관리_포트_주소가_값_없이_있으면_잡는다() {
        String yaml = baseCompose("").replace("{X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}",
                "[\"MANAGEMENT_SERVER_ADDRESS=0.0.0.0\", \"MANAGEMENT_SERVER_ADDRESS\"]");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("MANAGEMENT_SERVER_ADDRESS"));
    }

    @Test
    void 목록_env에서_관리_포트_주소가_두_번이면_잡는다() {
        String yaml = baseCompose("").replace("{X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}",
                "[\"MANAGEMENT_SERVER_ADDRESS=0.0.0.0\", \"MANAGEMENT_SERVER_ADDRESS=0.0.0.0\"]");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("app") && v.contains("MANAGEMENT_SERVER_ADDRESS"));
    }

    @Test
    void 목록_env에서_다른_변수가_값_없이_있는_건_통과한다() {
        String yaml = baseCompose("").replace("{X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}",
                "[\"X\", \"MANAGEMENT_SERVER_ADDRESS=0.0.0.0\"]");
        assertThat(violationsOf(yaml)).isEmpty();
    }

    @Test
    void 데이터_볼륨이_없으면_잡는다() {
        String yaml = baseCompose("").replace(
                "    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n",
                "    mem_limit: 256m\n");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("prometheus") && v.contains("볼륨"));
    }

    @Test
    void alloy의_docker_소켓이_읽기_전용이_아니면_잡는다() {
        String yaml = baseCompose("").replace(
                "/var/run/docker.sock:/var/run/docker.sock:ro", "/var/run/docker.sock:/var/run/docker.sock");
        assertThat(violationsOf(yaml)).anyMatch(v -> v.contains("alloy") && v.contains("docker.sock"));
    }

    // ---- 검사기 ----

    static List<String> violationsOf(String yaml) {
        return violations(yaml, new Yaml().load(yaml));
    }

    static List<String> violations(String text, Map<String, Object> root) {
        List<String> out = new ArrayList<>();
        Map<String, Object> services = services(root);

        if (java.util.regex.Pattern.compile("\\$\\{[^}]*:\\?").matcher(text).find()) {
            out.add("compose에 ${VAR:?} 보간이 있음 — ops 프로필을 안 써도 docker compose up이 실패함");
        }

        for (String name : OPS_SERVICES) {
            if (!services.containsKey(name)) {
                out.add(name + ": ops 서비스가 없음");
                continue;
            }
            Map<String, Object> s = asMap(services.get(name));

            if (!List.of("ops").equals(s.get("profiles"))) {
                out.add(name + ": profiles가 [ops]가 아님 — " + s.get("profiles"));
            }
            if (s.containsKey("ports")) {
                out.add(name + ": ports가 있음 — 내부 도구는 호스트 포트를 열지 않음");
            }
            String image = String.valueOf(s.get("image"));
            if (!pinnedImage(image)) {
                out.add(name + ": image가 고정되지 않음(태그 없음·latest·chainguard digest 아님) — " + image);
            }
            if (!ONE_SHOT.contains(name) && !HEALTHCHECK_EXEMPT.contains(name) && !s.containsKey("healthcheck")) {
                out.add(name + ": healthcheck이 없음");
            }
            if (!hasMemoryLimit(s)) {
                out.add(name + ": 메모리 상한(mem_limit 또는 deploy.resources.limits.memory)이 없음");
            }
            if (USES_SECRETS.contains(name) && !waitsForEnvCheck(s)) {
                out.add(name + ": depends_on에 ops-env-check(service_completed_successfully)이 없음");
            }
            if (STATEFUL.contains(name) && !hasNamedVolume(s, root)) {
                out.add(name + ": 이름 있는 데이터 볼륨이 없음");
            }
        }

        if (services.containsKey("alloy")) {
            Object vols = asMap(services.get("alloy")).get("volumes");
            boolean socketRo = vols instanceof List<?> l && l.stream().map(String::valueOf)
                    .anyMatch(v -> v.startsWith("/var/run/docker.sock:") && v.endsWith(":ro"));
            if (!socketRo) out.add("alloy: docker.sock을 읽기 전용(:ro)으로 붙이지 않음");
        }

        if (services.containsKey("app")) {
            Set<String> deps = dependsOn(asMap(services.get("app")));
            if (!deps.equals(Set.of("db"))) {
                out.add("app: depends_on이 db뿐이 아님 — " + deps);
            }
            String address = environment(asMap(services.get("app"))).get("MANAGEMENT_SERVER_ADDRESS");
            if (!"0.0.0.0".equals(address)) {
                out.add("app: environment의 MANAGEMENT_SERVER_ADDRESS가 0.0.0.0이 아님 — " + address
                        + " (기본 127.0.0.1이면 Prometheus가 app:8081에 못 들어옴)");
            }
            Map<String, Object> app = asMap(services.get("app"));
            if (app.containsKey("extends")) {
                out.add("app: extends가 있음 — 이 검사는 상속 풀기 전 정의만 읽어서 부모의 ports·network_mode를 못 봄");
            }
            if (app.get("environment") instanceof List<?> envList) {
                List<String> entries = envList.stream().map(String::valueOf)
                        .filter(e -> e.equals("MANAGEMENT_SERVER_ADDRESS") || e.startsWith("MANAGEMENT_SERVER_ADDRESS="))
                        .toList();
                if (entries.size() > 1 || entries.contains("MANAGEMENT_SERVER_ADDRESS")) {
                    out.add("app: 목록 environment에 MANAGEMENT_SERVER_ADDRESS가 값 없이 있거나 두 번 이상 — "
                            + "compose는 마지막 항목을 써서 앞의 0.0.0.0이 덮임 " + entries);
                }
            }
            if (app.get("ports") instanceof List<?> ports
                    && ports.stream().anyMatch(port -> publishesContainerPort(port, MANAGEMENT_PORT))) {
                out.add("app: ports에 컨테이너 쪽 " + MANAGEMENT_PORT + "(관리 포트)가 있음 — 지표가 호스트로 나감 " + ports);
            }
            if (app.containsKey("network_mode")) {   // 값 말고 키로 — ${X:-host} 같은 보간은 여기서 못 풂
                out.add("app: network_mode가 있음 — " + app.get("network_mode")
                        + " (host면 관리 포트 " + MANAGEMENT_PORT + "이 호스트 모든 주소에 열림, app은 기본 네트워크만)");
            }
        }
        for (String core : List.of("db", "app")) {
            if (services.containsKey(core) && asMap(services.get(core)).containsKey("profiles")) {
                out.add(core + ": profiles가 붙음 — 평소 docker compose up에서 빠짐");
            }
        }
        return out;
    }

    static final int MANAGEMENT_PORT = 8081;

    /**
     * ports 한 줄이 컨테이너 쪽 port 를 내보내는지. 짧은 문법은 마지막 ":" 뒤가 컨테이너 쪽
     * ("8081", "127.0.0.1:8081:8081", "18081:8081/tcp", "8080-8081:8080-8081"), 숫자 하나(8081)도 컨테이너 쪽,
     * 긴 문법은 target.
     */
    static boolean publishesContainerPort(Object entry, int port) {
        String container;
        if (entry instanceof Map<?, ?> m) {
            container = String.valueOf(m.get("target"));
        } else {
            String text = String.valueOf(entry);
            int slash = text.indexOf('/');
            if (slash >= 0) text = text.substring(0, slash);
            container = text.substring(text.lastIndexOf(':') + 1);
        }
        String[] range = container.trim().split("-", 2);
        try {
            int from = Integer.parseInt(range[0].trim());
            int to = range.length == 2 ? Integer.parseInt(range[1].trim()) : from;
            return from <= port && port <= to;
        } catch (NumberFormatException e) {
            return true;   // 못 읽는 모양(보간 등)은 내보낸다고 보고 위반으로 — 사람이 보게
        }
    }

    static boolean pinnedImage(String image) {
        if (image == null || image.equals("null")) return false;
        if (image.contains("@sha256:")) return image.matches(".+@sha256:[0-9a-f]{64}");
        if (image.startsWith("cgr.dev/chainguard/")) return false; // 무료판은 latest 태그만 → digest로만 고정
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        if (colon <= slash) return false; // 태그 없음 = latest
        String tag = image.substring(colon + 1);
        return !tag.isBlank() && !tag.equals("latest");
    }

    static boolean hasMemoryLimit(Map<String, Object> s) {
        if (s.get("mem_limit") != null) return true;
        Object deploy = s.get("deploy");
        if (!(deploy instanceof Map<?, ?> d)) return false;
        if (!(d.get("resources") instanceof Map<?, ?> r)) return false;
        return r.get("limits") instanceof Map<?, ?> l && l.get("memory") != null;
    }

    static boolean waitsForEnvCheck(Map<String, Object> s) {
        if (!(s.get("depends_on") instanceof Map<?, ?> deps)) return false;
        return deps.get("ops-env-check") instanceof Map<?, ?> c
                && "service_completed_successfully".equals(c.get("condition"));
    }

    static boolean hasNamedVolume(Map<String, Object> s, Map<String, Object> root) {
        if (!(s.get("volumes") instanceof List<?> vols)) return false;
        Map<String, Object> top = root.get("volumes") instanceof Map<?, ?> m ? asMap(m) : Map.of();
        return vols.stream().map(String::valueOf).map(v -> v.split(":")[0]).anyMatch(top::containsKey);
    }

    static Set<String> dependsOn(Map<String, Object> s) {
        Object d = s.get("depends_on");
        Set<String> out = new TreeSet<>();
        if (d instanceof Map<?, ?> m) m.keySet().forEach(k -> out.add(String.valueOf(k)));
        if (d instanceof List<?> l) l.forEach(k -> out.add(String.valueOf(k)));
        return out;
    }

    /** environment 는 맵({K: v})·목록(["K=v"]) 둘 다 된다. 값은 문자열로. */
    static Map<String, String> environment(Map<String, Object> s) {
        Map<String, String> out = new java.util.HashMap<>();
        Object e = s.get("environment");
        if (e instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        if (e instanceof List<?> l) l.stream().map(String::valueOf).filter(x -> x.contains("="))
                .forEach(x -> out.put(x.substring(0, x.indexOf('=')), x.substring(x.indexOf('=') + 1)));
        return out;
    }

    static Map<String, Object> services(Map<String, Object> root) {
        return asMap(root.get("services"));
    }

    static Map<String, Object> service(Map<String, Object> root, String name) {
        return asMap(services(root).get(name));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 규칙을 모두 지킨 최소 compose. 각 실패 테스트가 한 곳만 바꿔서 위반을 만든다. */
    static String baseCompose(String extra) {
        String wait = "    depends_on: {ops-env-check: {condition: service_completed_successfully}}\n";
        StringBuilder sb = new StringBuilder();
        sb.append("name: t\nservices:\n");
        sb.append("  db:\n    image: pgvector/pgvector:0.8.2-pg16-trixie\n");
        sb.append("  app:\n    build: .\n    depends_on: {db: {condition: service_healthy}}\n    environment: {X: y, MANAGEMENT_SERVER_ADDRESS: 0.0.0.0}\n");
        sb.append("  ops-env-check:\n    profiles: [ops]\n    image: alpine:3.20\n    mem_limit: 32m\n");
        sb.append("  prometheus:\n    profiles: [ops]\n    image: prom/prometheus:v3.16.0\n")
          .append("    healthcheck: {test: [\"CMD\", \"true\"]}\n    mem_limit: 256m\n    volumes: [\"prometheus-data:/prometheus\"]\n");
        sb.append("  grafana:\n    profiles: [ops]\n").append(wait)
          .append("    image: grafana/grafana:13.3.1\n    environment: {GF_SECURITY_ADMIN_PASSWORD: \"${GRAFANA_ADMIN_PASSWORD:-}\"}\n")
          .append("    healthcheck: {test: [\"CMD\", \"true\"]}\n    mem_limit: 256m\n    volumes: [\"grafana-data:/var/lib/grafana\"]\n");
        sb.append("  loki:\n    profiles: [ops]\n    image: grafana/loki:3.7.10\n    mem_limit: 256m\n    volumes: [\"loki-data:/loki\"]\n");
        sb.append("  alloy:\n    profiles: [ops]\n    image: grafana/alloy:v1.22.0\n")
          .append("    healthcheck: {test: [\"CMD\", \"true\"]}\n    mem_limit: 128m\n")
          .append("    volumes: [\"/var/run/docker.sock:/var/run/docker.sock:ro\"]\n");
        for (String[] s : new String[][]{
                {"redis", "redis:8.10.3-alpine", "redis-data:/data"},
                {"rabbitmq", "rabbitmq:4.4.0-management-alpine", "rabbitmq-data:/var/lib/rabbitmq"},
                {"minio", "cgr.dev/chainguard/minio@sha256:" + "a".repeat(64), "minio-data:/data"},
                {"elasticsearch", "docker.elastic.co/elasticsearch/elasticsearch:9.5.6", "es-data:/usr/share/elasticsearch/data"},
                {"clickhouse", "clickhouse/clickhouse-server:26.3.62.3", "clickhouse-data:/var/lib/clickhouse"},
                {"langfuse-worker", "docker.langfuse.com/langfuse/langfuse-worker:4.82.0", null},
                {"langfuse-web", "docker.langfuse.com/langfuse/langfuse:4.82.0", null}}) {
            sb.append("  ").append(s[0]).append(":\n    profiles: [ops]\n").append(wait)
              .append("    image: ").append(s[1]).append("\n")
              .append("    healthcheck: {test: [\"CMD\", \"true\"]}\n    mem_limit: 256m\n");
            if (s[2] != null) sb.append("    volumes: [\"").append(s[2]).append("\"]\n");
        }
        sb.append("  minio-init:\n    profiles: [ops]\n").append(wait)
          .append("    image: cgr.dev/chainguard/minio-client@sha256:").append("b".repeat(64)).append("\n    mem_limit: 64m\n");
        sb.append("  langfuse-db-init:\n    profiles: [ops]\n").append(wait)
          .append("    image: pgvector/pgvector:0.8.2-pg16-trixie\n    mem_limit: 64m\n");
        sb.append(extra);
        sb.append("volumes:\n");
        for (String v : List.of("prometheus-data", "grafana-data", "loki-data", "redis-data", "rabbitmq-data",
                "minio-data", "es-data", "clickhouse-data")) {
            sb.append("  ").append(v).append(":\n");
        }
        return sb.toString();
    }
}
