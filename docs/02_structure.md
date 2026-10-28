# 02. 폴더 구조와 의존 방향

> v0.1 · 2026-10-29 · 초안(팀 확인 중) · 정리 진희

## 1. 폴더 — 기능이 먼저, 그 안에 계층 [가이드 §3.1, 레퍼런스 §4.1, 코드 `package-info.java`]

```
com.team.aml
├─ source      적재                 준혁
├─ detection   룰·경보·점수 배치     준혁
├─ cases       조사                 지윤
├─ str         STR 초안·승인         채영
├─ user        로그인·사용자          진희 (W4 사용자 관리는 "먼저 끝난 사람, 없으면 진희")
└─ shared      공통                  진희 — 다른 사람은 PR로 요청
   ├─ domain    BaseEntity, CreatedOnlyEntity, DomainException, NotFoundException, DisplayNumber
   ├─ api       GlobalExceptionHandler, ErrorResponse, PageResponse
   ├─ security  SecurityConfig, AppUserDetails, CurrentUser, SessionAuditorAware, 401/403 JSON, request_id·user_id 필터
   ├─ config    JPA Auditing, OpenAPI, AmlProperties
   ├─ batch     BatchRun, BatchRunRepository, BatchType, BatchStatus, 409 예외 2개
   └─ ai        AiClient, AiDtos, MockAiClient, AiHealthCheck (WebClient 구현·ai_call_logs는 W3)

각 기능 폴더 안은 똑같이:
  domain/          엔티티 + 상태 enum + 업무 규칙 + 리포지토리 "인터페이스"
  application/     유스케이스 (한 클래스 = 한 일, execute 하나)
  infrastructure/  JPA 리포지토리 구현, 네이티브 쿼리, 바깥 연동
  api/             컨트롤러 + 요청·응답 record
```

- **폴더 하나 = 담당자 한 명.** PR 리뷰도 폴더 단위로 해요.
- Flyway SQL은 기능 폴더 밖 `src/main/resources/db/migration/` 한 곳에 모아요(번호 순서 때문). [레퍼런스 §4.1]
- AI용 DTO는 `shared/ai/`에 있어요. [레퍼런스 §4.1]

| 익숙한 폴더 | 여기서는 | 다른 점 |
|---|---|---|
| `entity/` | `{기능}/domain/` | setter 없음, `escalate()` 같은 업무 메서드. 상태 enum도 여기 |
| `service/` | `{기능}/application/` | 서비스 하나에 메서드 열 개 대신 **유스케이스 하나 = 클래스 하나** |
| `repository/` | 인터페이스는 `domain/`, 구현은 `infrastructure/` | 엔티티가 JPA 구현을 모르게 |
| `controller/`, `dto/` | `{기능}/api/` | 컨트롤러와 그 요청·응답 record를 한 폴더에 |

## 2. 계층 의존 방향 [레퍼런스 §4.2]

`api → application → domain ← infrastructure`. 반대 방향 import 금지.

| 계층 | 들어가는 것 | 들어가면 안 되는 것 |
|---|---|---|
| domain | 엔티티, 값 객체, 상태 enum, 리포지토리 인터페이스, 도메인 예외 | `@Service`, `@Transactional`, HTTP 클라이언트, DTO, 다른 계층 import |
| application | 유스케이스(`@Service` + `@Transactional`), 리포지토리 인터페이스 호출, `AiClient` 인터페이스 호출 | SQL, HTTP 코드, `@Controller`, 엔티티를 그대로 반환 |
| infrastructure | JPA 리포지토리 구현, `AiClient` 구현, 설정 | 업무 규칙(상태 검사 if 문 등) |
| api | `@RestController`, 요청·응답 record, `@Valid` | 리포지토리 직접 호출, 엔티티 노출 |

## 3. 기능끼리 부를 때 [가이드 §3.3, 레퍼런스 §4.1]

의존 방향: **`source ← detection ← cases ← str`**, `user`는 모두가 읽기만, `shared`는 기능을 몰라요. 화살표 반대는 없어요.

| 해도 되는 것 | 하면 안 되는 것 |
|---|---|
| 다른 기능의 **application 유스케이스** 호출 (`str` → `cases.application.AttachDraftToCase`) | 다른 기능의 **infrastructure** 직접 호출 (`str` → `CaseJpaRepository`) |
| 다른 기능의 domain 엔티티를 **읽기로** 참조 | 다른 기능의 엔티티 **상태를 바꾸기** (`Case`는 `cases`만 바꿔요) |
| **ID로** 참조 (`StrDraft.caseId`) | **객체로** 참조 (`StrDraft.case`) |
| `shared/`는 어디서나 | `shared/`가 기능 폴더를 참조 |

필요한 유스케이스가 상대 폴더에 없으면 직접 만들지 말고 담당자에게 만들어 달라고 해요.

**뒤쪽 기능이 앞쪽 기능을 바꿔야 할 때** — 의존 방향 때문에 `cases`는 `str`을 부를 수 없어요. 그래서 "초안이 있는 케이스 종료"처럼 두 기능을 같이 바꾸는 일은 **뒤쪽(`str`) 유스케이스가 시작**하고 앞쪽(`cases`)의 공개 유스케이스를 같은 트랜잭션에서 불러요. [안건 24, STR 문서, CASES 명세 §6.2] 같은 이유로 케이스 생성 배치는 `cases`에 있고, `detection`은 `MarkCaseOpened`만 제공해요. [레퍼런스 §11]

## 4. 애그리거트 두 개 [레퍼런스 §4.3]

| 애그리거트 | 루트 | 안에 포함 | 바깥에서 참조 |
|---|---|---|---|
| `Case` | `Case` | `CaseHistory` 목록 | `StrDraft`는 `caseId`(Long)만 |
| `StrDraft` | `StrDraft` | `StrRevision`, `VerificationResult` 목록, `Approval` | `Case`는 `currentDraftId`(Long)만 |

- 애그리거트 하나 = 트랜잭션 하나가 원칙. **예외:** 승인(`ApproveStr`)·반려·초안 저장·초안 있는 종료처럼 두 루트를 같이 바꾸는 협력 트랜잭션은 `str` 유스케이스가 시작하고 `cases`가 참여해요(`REQUIRED`, `REQUIRES_NEW` 아님). [레퍼런스 §4.3, CASES 명세 §6.2·§8.2 〔초안〕]
- `Alert`, `Rule`, `AlertScore`는 애그리거트가 아니에요(배치가 만들고 사람이 안 고침). `Transaction`, `Account`, `Entity`는 `@Immutable`.

## 5. `ArchitectureTest`가 CI에서 막는 것 [코드 `src/test/java/com/team/aml/ArchitectureTest.java`]

DB 없이 돌고, 어기면 빌드가 빨갛게 돼요. 아직 클래스가 없는 폴더는 통과해요(`allowEmptyShould`).

| 테스트 | 막는 것 |
|---|---|
| `기능_간_의존_방향` | `source`→(detection·cases·str), `detection`→(cases·str), `cases`→str, `user`→(source·detection·cases·str), `shared`→(모든 기능) 의존 |
| `다른_기능의_infrastructure_직접_호출_금지` | 기능 폴더 밖에서 그 기능의 `infrastructure` 패키지를 쓰는 것 |
| `계층_의존_방향` | 기능마다: `api`는 누구도 못 부름 · `application`은 `api`만 부름 · `domain`은 api·application·infrastructure가 씀 · `infrastructure`는 누구도 못 부름 |
| `domain_은_Spring_을_모른다` | `*.domain` 패키지가 `org.springframework.web/stereotype/transaction/security`를 쓰는 것 (JPA·Spring Data 애노테이션은 허용) |

## 6. `ArchitectureTest`가 **못** 막는 것 — 리뷰에서 봐야 해요

테스트 규칙을 읽어 보면 아래는 통과돼요. 리뷰 체크리스트(`07`)에 넣어 뒀어요.

| 못 막는 것 | 왜 통과하나 | 규칙 출처 |
|---|---|---|
| 컨트롤러가 **리포지토리 인터페이스**를 바로 부름 | 리포지토리 인터페이스가 `domain`에 있고, `api → domain`은 허용 | 가이드 §3 실수, 레퍼런스 §4.2 |
| 엔티티를 응답으로 그대로 반환 | 계층 규칙만 봄 | 레퍼런스 §4.2 |
| `@Transactional`이 컨트롤러·리포지토리 구현에 붙음 | domain만 `spring.transaction`을 막음 | 레퍼런스 §8.4 |
| 트랜잭션 안에서 `AiClient` 호출 | 실행 흐름은 정적 분석으로 안 보임 | 가이드 §5 |
| 엔티티에 public setter, `setStatus()` | 검사 규칙 없음 | README 규칙 2 |
| 다른 기능의 엔티티 상태를 바꾸는 메서드 호출 | `domain` 읽기 참조는 허용이라 호출도 통과 | 레퍼런스 §4.1 |
| 애그리거트 간 객체 참조(`@ManyToOne Case case`) | 같은 규칙 | 레퍼런스 §4.3 |
| 응답에 정답 라벨·계좌번호 원문 노출 | 데이터 규칙 | 레퍼런스 §10 |

## 7. 자동 검사로 옮길 후보 〔제안〕

위 표 중 ArchUnit으로 잡을 수 있는 것들이에요. 리뷰에서 사람이 매번 보는 것보다 CI가 막는 게 확실해요. 팀이 좋다고 하면 진희가 `ArchitectureTest`에 추가 PR을 올릴게요.

| 후보 규칙 | 막는 것 | 난이도 |
|---|---|---|
| `api` 패키지 클래스는 `*Repository`에 의존하지 않는다 | 컨트롤러가 리포지토리 직접 호출 | 쉬움 |
| `@Transactional`은 `application` 패키지에만 | 컨트롤러·리포지토리 구현의 트랜잭션 | 쉬움 |
| `@Entity` 클래스에 `public void set*` 메서드가 없다 (Lombok `@Setter` 포함) | `setStatus()` | 쉬움 |
| `@RestController` 메서드의 반환 타입이 `@Entity`가 아니다 | 엔티티 노출 | 보통 |
| 다른 기능의 `domain` 클래스는 읽기 전용 메서드만 호출 | 다른 기능 엔티티 상태 변경 | 어려움 — 리뷰로 |

리뷰에서 **서로 다른 사람에게 같은 지적이 두 번** 나오면 이 표에 올려요.
