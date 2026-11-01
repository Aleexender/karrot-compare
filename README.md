# karrot-compare

같은 "중고물품 판매 게시글 등록 API"를 두 방식으로 만든 비교 프로젝트입니다.

| 디렉터리 | 방식 | 프롬프트 |
|---|---|---|
| [`with-design/`](with-design) | 요구사항 + 설계를 주고 서브에이전트(planner → architect → developer → tester → qa) 파이프라인으로 개발 | [01-post-create-requirements.md](with-design/docs/01-post-create-requirements.md) |
| [`no-design/`](no-design) | 설계 없이 한 줄 프롬프트로 개발 | [01-post-create-prompt.md](no-design/docs/01-post-create-prompt.md) |

버그율 측정: [docs/bug-rate-comparison.md](docs/bug-rate-comparison.md) — 51개 시나리오(요구서 기반 24 + 일반 API 행동 27)에서 with-design 1건(2.0%), no-design 14건(27.5%) 실패

with-design 문서: [설계서](with-design/docs/02-post-create-design.md) · [QA 보고서](with-design/docs/03-post-create-qa.md)

## 실행

```bash
docker compose up -d                       # MySQL 9.7 (localhost:3307), DB 2개 생성
cd with-design && ./gradlew bootRun        # :8081
cd no-design   && ./gradlew bootRun        # :8082
```

스택: Spring Boot 4.1, Java 25, Spring Data JPA, MySQL 9.7. DB 계정(`karrot`/`karrot`, root `root`)은 로컬 개발용입니다.

## 시퀀스 다이어그램

### with-design — `POST /posts`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant IC as ItemController<br/>(presentation)
    participant IS as ItemService<br/>(application, 트랜잭션 없음)
    participant CR as ItemCreator<br/>(@Transactional)
    participant RD as ItemReader<br/>(@Transactional readOnly)
    participant EH as ItemExceptionHandler
    participant DB as MySQL

    C->>IC: POST /posts<br/>X-User-Id, Idempotency-Key, body
    alt Bean Validation 실패 (제목·가격·카테고리·키·JSON 형식)
        IC-->>C: 400 ProblemDetail
    end
    alt X-User-Id 없음 / 형식 오류
        IC->>EH: UnauthenticatedUserException
        EH-->>C: 401 ProblemDetail
    end
    IC->>IC: request.toCommand(userId, key)
    IC->>IS: register(RegisterItemCommand)
    IS->>CR: create(command)

    rect rgb(235, 245, 255)
        Note over CR,DB: 트랜잭션 A (쓰기)
        CR->>DB: users existsById
        alt 유저 없음
            CR-->>EH: UnauthenticatedUserException
            EH-->>C: 401 ProblemDetail
        end
        CR->>DB: item_categories existsById
        alt 카테고리 없음
            CR-->>EH: UnknownCategoryException
            EH-->>C: 400 ProblemDetail
        end
        CR->>CR: new Title / Content / Price (VO 불변식)<br/>new Item(status = ON_SALE)
        CR->>DB: INSERT items
    end

    alt 신규 저장 성공 (COMMIT)
        DB-->>CR: id
        CR-->>IS: Item
        IS-->>IC: ItemRegistration(item, created=true)
        IC-->>C: 201 Created + Location: /posts/{id}
    else UNIQUE(user_id, idempotency_key) 충돌 (1062) → 트랜잭션 A 롤백
        DB-->>CR: Duplicate entry
        CR-->>IS: DataIntegrityViolationException
        IS->>IS: 제약 이름이 uk_items_user_idempotency인지 확인
        rect rgb(240, 255, 240)
            Note over RD,DB: 트랜잭션 B (새 readOnly) — 먼저 커밋된 행이 보임
            IS->>RD: getByIdempotencyKey(userId, key)
            RD->>DB: SELECT items WHERE user_id=? AND idempotency_key=?
            DB-->>RD: 최초 게시글
        end
        RD-->>IS: Item
        IS-->>IC: ItemRegistration(item, created=false)
        IC-->>C: 200 OK + 최초 게시글
    else 커넥션 대기 초과 / 잠금 대기 초과 / deadlock
        Note over C,DB: TransientDatabaseFailureHandler
        IS-->>C: 503 ProblemDetail
    end
```

### with-design — `GET /posts/{id}`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant IC as ItemController
    participant RD as ItemReader<br/>(@Transactional readOnly)
    participant EH as ItemExceptionHandler
    participant DB as MySQL

    C->>IC: GET /posts/{id} (헤더 불필요)
    alt id 형식 오류
        IC-->>C: 400 ProblemDetail
    end
    IC->>RD: get(id)
    RD->>DB: SELECT items WHERE id=?
    alt 존재
        DB-->>RD: row
        RD-->>IC: Item
        IC-->>C: 200 OK + ItemResponse
    else 없음
        RD-->>EH: ItemNotFoundException
        EH-->>C: 404 ProblemDetail
    end
```

### no-design — `POST /api/posts`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant PC as PostController
    participant PR as PostRepository<br/>(Spring Data JPA)
    participant DB as MySQL

    C->>PC: POST /api/posts<br/>body: sellerId, title, content, price, location
    alt Bean Validation 실패
        PC-->>C: 400 (Spring 기본 오류 응답)
    end
    PC->>PC: new Post(...)<br/>status = SELLING, createdAt = now()
    PC->>PR: save(post)
    Note over PR,DB: SimpleJpaRepository.save의 기본 트랜잭션
    PR->>DB: INSERT posts
    DB-->>PR: id
    PR-->>PC: Post
    PC-->>C: 201 Created + PostResponse (Location 헤더 없음)
```

## 두 구현의 차이

| 항목 | with-design | no-design |
|---|---|---|
| 경로 | `POST /posts` (요구서대로) | `POST /api/posts` |
| 작성자 | `X-User-Id` 헤더 → 존재 확인, 없으면 401 | 본문의 `sellerId`를 존재 확인 없이 그대로 저장 |
| 등록 버튼 연타 | `Idempotency-Key` + UNIQUE 제약 + 트랜잭션 밖 재조회 → 1건 저장, 두 번째는 200 | 중복 방지 장치 없음 (코드 기준, 실행 검증은 안 함) |
| 카테고리 | `categoryId` 필수, 존재 확인 | 없음 |
| 네고 가능 | 있음 (생략하면 false) | 없음 |
| 내용 | 비어도 됨 | `@NotBlank` → 비면 400 |
| 판매 상태 | `ON_SALE` / `SOLD` / `STOPPED` | `SELLING` / `RESERVED` / `SOLD` |
| 계층 | DDD 4계층: presentation → application → domain | Controller가 Repository 직접 호출 |
| 트랜잭션 | 생성(쓰기)과 재조회(readOnly) 분리 | `save()` 기본 트랜잭션 |
| 오류 응답 | ProblemDetail, 400/401/404/503 | Spring 기본 오류 응답 |
| 조회 API | `GET /posts/{id}` | 없음 |
| 스키마 | `schema.sql` (UNIQUE, FK, CHECK) | `ddl-auto=update` |
| 테스트 | 92개 (단위·WebMvc·통합·동시성) | 기본 `contextLoads` 1개 |
| 완료 기준 (동시 1만 건 중 9,500) | 3회 측정 모두 10,000/10,000 | 측정 안 함 |
