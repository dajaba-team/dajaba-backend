plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.team"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17   // JDK 17 또는 21 로 빌드 가능 (release 17)
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

repositories { mavenCentral() }

// Spring Boot 3.3.5 기본 1.19.8 은 Docker API 1.32 로 말을 걸어 GitHub 러너 Docker 29 가 거절한다 → 1.x 수정판 (08_decisions)
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")      // WebClient (AiClient)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")   // /actuator/prometheus (관리 포트 8081, SCRUM-271). 버전은 Boot BOM
    implementation("org.springframework.boot:spring-boot-starter-amqp")       // RabbitMQ 작업 큐 (SCRUM-276, 기본 꺼짐)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")  // 로그인 세션 저장소 (SCRUM-273, 기본 꺼짐 — 자동 설정은 application.yml 에서 막음)
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    // STR 보고서 PDF (S7·S8·P5). HTML → PDF, PDFBox 3.0.x 를 같이 끌어온다. LGPL 2.1 — 고치지 않고 jar 로만 쓴다
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.31")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    // STR PDF 저장소 — MinIO 및 AWS S3의 S3 호환 API (MinIO → AWS S3 이전 시 구현 재사용)
    implementation("software.amazon.awssdk:s3:2.29.26")
    implementation("org.postgresql:postgresql")   // 적재 COPY에 CopyManager 사용

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("com.squareup.okhttp3:mockwebserver")   // AiClient 단위 테스트 (SCRUM-17 D4)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // CI는 UTC라 시간대 버그가 드러나지 않는다. KST로 돌려 ts가 9시간 밀리는 회귀를 잡는다.
    systemProperty("user.timezone", "Asia/Seoul")
    // 기본 512m에선 @SpringBootTest 컨텍스트가 여러 벌 캐시돼 CI에서 OutOfMemoryError가 난다(11/12, 08_decisions).
    maxHeapSize = "1536m"
}
