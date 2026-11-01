# 02. 게시글 등록 API — 설계 결정 (확정)

요구서: [01-post-create-requirements.md](01-post-create-requirements.md)
검토: planner 분석 + architect 검토(조건부 승인) → 제안 전부 반영, 사용자 결정 반영 (2026-10-29)

## API

### `POST /posts` — 게시글 등록

| 헤더 | 필수 | 설명 |
|---|---|---|
| `X-User-Id` | O | 작성자 ID. 로그인은 범위 밖이라 임시로 헤더에서 받음 |
| `Idempotency-Key` | O | **작성 화면 1개(작성 시도 1회)당** 클라이언트가 만드는 고유값 (UUID 권장, 1~64자 ASCII). 같은 화면에서 반복 클릭·재시도할 때는 같은 키를 다시 쓰고, 등록 성공 또는 화면 이탈 시에만 버림 |

```json
{ "title": "맥북 에어 M3", "content": "거의 새것", "price": 1200000, "negotiable": true, "categoryId": 1 }
```

| 필드 | 타입 | 규칙 |
|---|---|---|
| `title` | String | 필수. 공백만 있으면 안 됨(`String.isBlank` 기준). 100자 이하. "자"는 유니코드 코드 포인트 수(MySQL `CHAR_LENGTH`와 같음). 이모지 1개 = 1자, 결합 문자·ZWJ 이모지는 구성 코드 포인트 수만큼 셈 |
| `content` | String | 선택(누락, null, 빈 문자열 허용). 5000자 이하(코드 포인트 기준, title과 같음) |
| `price` | Long | 필수. 0 이상 (0 = 나눔) |
| `negotiable` | Boolean | 선택. 생략하면 `false` |
| `categoryId` | Long | 필수. `item_categories`에 존재해야 함 |
| (판매 상태) | — | 요청으로 받지 않음. 등록 시 항상 `ON_SALE` |

| 결과 | 상태 코드 |
|---|---|
| 신규 등록 | 201 + `Location: /posts/{id}` + 게시글 본문 |
| 같은 (유저, 키) 재요청 | 200 + 최초에 만든 게시글 (새로 만들지 않음. 본문이 달라도 최초 글을 반환) |
| 제목 없음/공백/100자 초과, 가격 없음/음수, 카테고리 없음/존재하지 않음, 내용 5000자 초과, 키 없음/공백/64자 초과, JSON 형식 오류, 필드 타입 오류 | 400 |
| `X-User-Id` 없음/형식 오류/존재하지 않는 유저 | 401 |
| DB 커넥션 대기 초과, 잠금 대기 초과, deadlock | 503 |

### `GET /posts/{id}` — 게시글 단건 조회

- 헤더가 필요 없음. 로그인하지 않은 사용자도 조회할 수 있음 (사용자 결정).
- 등록 응답의 `Location`이 가리키는 리소스.

| 결과 | 상태 코드 |
|---|---|
| 존재 | 200 + 게시글 본문 |
| 존재하지 않음 | 404 |
| id 형식 오류 | 400 |

### 게시글 응답 본문 (등록 201/200, 조회 200 공통)

```json
{ "id": 1, "userId": 7, "categoryId": 1, "title": "맥북 에어 M3", "content": "거의 새것",
  "price": 1200000, "negotiable": true, "status": "ON_SALE", "createdAt": "2026-10-29T03:00:00.123456" }
```

에러 응답은 ProblemDetail 형식.

## 결정 사항

| 항목 | 결정 | 이유 |
|---|---|---|
| 중복 방지 | `items`에 UNIQUE(user_id, idempotency_key) | 동시에 와도 DB 제약이 최종 판정. 애플리케이션 락이나 Redis가 필요 없음 |
| 충돌 처리 | INSERT는 생성 전용 트랜잭션에서 수행. 충돌(constraint `uk_items_user_idempotency`, MySQL 1062)은 **그 트랜잭션 바깥**에서 잡고, **새 readOnly 트랜잭션**에서 (user_id, key)로 조회해 200 반환. 같은 트랜잭션에서 재조회하지 않음. constraint 이름이 다른 무결성 오류(FK 1452, 길이 1406 등)는 중복으로 취급하지 않음 | 같은 트랜잭션에서 재조회하면 rollback-only로 커밋 시 500, REPEATABLE-READ 스냅샷 때문에 상대 행이 보이지 않음 |
| 키 소비 시점 | 키는 저장에 성공했을 때만 소비됨. 400 후 내용을 고쳐 같은 키로 재전송하면 201 | 키가 items 행에 저장되기 때문 |
| 판매 상태 | 등록 시 항상 `ON_SALE`. `SOLD`, `STOPPED`는 이후 수정 API에서 사용. 엔티티는 `@Enumerated(STRING)`, DB에 `CHECK (status IN ('ON_SALE','SOLD','STOPPED'))` | 처음부터 판매완료로 올라오는 비정상 상태를 막음 |
| 네고 가능 | 생략하면 `false` | 요구서상 필수 입력이 아님 |
| 내용 | 선택 입력, 5000자(코드 포인트) 이하. 생략·null은 NULL로, 빈 문자열은 `''`로 저장 | 요구서: "내용은 빌 수 있다" |
| 가격 | DB `BIGINT` + `CHECK (price >= 0)`. 요청 DTO는 `Long` + `@NotNull` + `@PositiveOrZero` (0 = 나눔) | primitive `long`이면 누락 시 0으로 채워져 "가격 없음"이 201로 저장될 수 있음 |
| 카테고리 | `item_categories` 테이블 (1 IT기기, 2 옷/패션, 3 가전제품), `data.sql`로 초기 데이터 입력. `categoryId`는 필수(`Long` + `@NotNull`) | 요구서가 item_categories를 도메인으로 명시 (01 L8). 필수 여부는 사용자 결정 |
| 의존 방향 | 다른 Aggregate는 ID로 참조: `Item.userId`, `Item.categoryId` (`Long`, DB FK는 유지). 객체 연관 없음. `user.domain`과 `category.domain`은 다른 도메인을 import하지 않음. 존재 확인은 `item.application`이 Repository로 수행 | 요구서: users는 item_categories를 몰라야 함. 객체 연관이 없으므로 LAZY 프록시와 역방향 컬렉션 문제가 생기지 않음 |
| 패키지 | 도메인 단위 + 계층(DDD 4계층: presentation → application → domain). 공통 설정은 `global.config`, 공통 오류 처리는 `global.error`. 클래스 배치는 아래 "패키지 구조" 절. infrastructure 계층은 둘 클래스가 생길 때 만든다 | 도메인 경계를 패키지로 드러내고, 계층별 책임을 분리 |
| 인터페이스 | Repository만 인터페이스로 둠(Spring Data 인터페이스를 domain에 둠). Service, Controller, 예외 핸들러는 class, VO와 Command는 record | 요구서 |
| 값 객체 | `Title`, `Content`, `Price`를 `@Embeddable record`로 둠. 검증은 compact 생성자에 있어 조회 시에도 검사됨(DB에 수동으로 넣은 규칙 위반 행은 GET 시 500). 길이는 정규화 없이 코드 포인트로 셈 | 규칙의 원본을 한 곳에 둠. 코드 포인트가 DB 판정과 정확히 일치하는 유일한 단위 |
| 검증 역할 | 요청 DTO(Bean Validation)가 400을 판정하는 1차 관문(`@NotBlank`, `@CodePointLength` 등). VO는 같은 규칙을 불변식으로 다시 검사하며, 숫자는 VO 상수(`Title.MAX_LENGTH` 등)를 공유. VO 예외는 HTTP로 매핑하지 않음(도달하면 버그 → 500) | 400 ProblemDetail의 형식과 판정 순서 유지, 잘못된 입력은 트랜잭션을 열지 않음 |
| 예외 매핑 | 업무 예외(`UnauthenticatedUserException`, `UnknownCategoryException`, `ItemNotFoundException`)는 HTTP를 모르는 `RuntimeException`. `item.presentation.ItemExceptionHandler`가 401/400/404로, `global.error.TransientDatabaseFailureHandler`가 503으로 매핑 | domain과 application이 spring-web에 의존하지 않음 |
| application 입출력 | 입력은 `RegisterItemCommand` record, 출력은 domain `Item`. presentation이 DTO ↔ Command/응답을 변환 | application이 presentation DTO에 의존하지 않음. ID 참조라 LAZY 연관이 없어 트랜잭션 밖 변환도 안전 |
| ID 생성 | `@GeneratedValue(strategy = IDENTITY)` | schema.sql이 AUTO_INCREMENT, `ddl-auto=none`. `save()` 시점에 즉시 INSERT되어 충돌 예외 위치가 명확 |
| 멱등 키 컬럼 | `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin` | 기본 `utf8mb4_0900_ai_ci`는 `'abc'='ABC'`로 판정 (실측) |
| 시간대 | `created_at`은 UTC로 저장 (`spring.jpa.properties.hibernate.jdbc.time_zone=UTC`) | DB는 UTC, 호스트는 KST |
| 스키마 관리 | `schema.sql`이 기준. `ddl-auto=none` | 제약조건(UNIQUE, FK, CHECK)을 코드 리뷰에서 눈으로 확인 |
| 스키마 적용 | `schema.sql`은 `CREATE TABLE IF NOT EXISTS`라 기존 테이블에 변경이 반영되지 않음. 이번 변경(CHECK, ascii_bin)은 개발 DB 테이블을 DROP 후 재생성해서 적용 | 개발 DB에 출처 불명 잔여 데이터(items 10,003행, users 1,000행)가 있음 |
| 동시 처리 | Virtual Thread, Hikari 풀 50, `spring.datasource.hikari.connection-timeout=35000`, `server.tomcat.max-connections=10500` | 풀 50 ≤ MySQL max_connections 151 (실측). 1만 동시 소켓 > Tomcat 기본 8192+100. 9,500건 성공 조건: 50 + T × timeout ≥ 9,500 → T = 315 TPS일 때 timeout ≥ 30s. 처리량 T는 구현 후 실측 |
| 존재 확인 | INSERT 전에 `existsById`로 유저(401)와 카테고리(400)의 존재를 확인. VO는 존재 확인 뒤에 생성(판정 순서 유지) | 판정이 명확. 14 TPS에서 비용 무시 가능. 1만 burst의 병목은 커밋 fsync |

## 패키지 구조

```
com.example.karrot
├─ KarrotApplication
├─ global/
│  ├─ config/JacksonCoercionConfig
│  └─ error/TransientDatabaseFailureHandler        503
├─ item/
│  ├─ presentation/
│  │  ├─ ItemController            X-User-Id 파싱, Command 생성, ItemResponse 변환
│  │  ├─ ItemExceptionHandler      401 / 400 / 404 → ProblemDetail
│  │  └─ dto/ CreateItemRequest (Bean Validation + toCommand), ItemResponse (from(Item))
│  ├─ application/
│  │  ├─ ItemService               트랜잭션 없음. 1062 판정 후 ItemReader로 재조회
│  │  ├─ ItemCreator               @Transactional: existsById ×2 → VO 생성 → save
│  │  ├─ ItemReader                @Transactional(readOnly = true)
│  │  ├─ ItemRegistration          record(Item item, boolean created)
│  │  ├─ RegisterItemCommand
│  │  └─ exception/ ItemNotFoundException, UnknownCategoryException, UnauthenticatedUserException
│  └─ domain/
│     ├─ Item (Aggregate Root), ItemStatus
│     ├─ Title, Content, Price     @Embeddable record VO
│     └─ ItemRepository
├─ category/domain/ ItemCategory, ItemCategoryRepository
└─ user/domain/     User, UserRepository
```

| VO | 불변식 | 컬럼 |
|---|---|---|
| `Title` (`MAX_LENGTH = 100`) | null 아님, `isBlank()` 아님, 코드 포인트 ≤ 100 | `@Column(name = "title", nullable = false, length = 100)` |
| `Content` (`MAX_LENGTH = 5000`) | null 아님(내용 없음은 `Item.content` 필드 null), 코드 포인트 ≤ 5000, 빈 문자열 허용 | `@Column(name = "content")` |
| `Price` | ≥ 0 | `@Column(name = "price", nullable = false)` |

의존 규칙:
- presentation → application, domain(VO 상수, `ItemStatus`).
- application → domain, `user.domain.UserRepository`, `category.domain.ItemCategoryRepository`.
- domain → JPA, Spring Data 외에 아무것도 참조하지 않음. `org.springframework.web` 금지.
- `user`, `category`는 다른 도메인을 참조하지 않음. `global`은 도메인을 참조하지 않음.

## 트래픽 관점
- 게시글 등록은 쓰기 작업입니다. DAU 120만 명이 하루 1건씩 올린다고 극단적으로 가정해도 평균 13.9 TPS(1,200,000 / 86,400)입니다.
- 피크를 평균의 10배로 잡아도 139 TPS × 트랜잭션 10ms ≈ 커넥션 1.4개입니다. 풀 크기는 1만 burst 테스트가 결정합니다.
- 1만 건 동시 요청은 이 수준을 크게 넘는 순간 폭주(burst) 테스트입니다.
- "인기 게시글 12만"은 조회(읽기) 트래픽이라 등록 API와는 무관합니다. `GET /posts/{id}`는 이번에 단건 조회만 만들고, 인기 게시글 부하 대응(캐시 등)은 별도 요구가 오면 설계합니다.

## 검증 계획 (구현 후 결과를 날짜·명령과 함께 QA 보고서에 기록)
- 전제: 측정 전 `items` 비우기. 부하용 유저 1,000명은 QA가 임시 SQL로 넣고 보고서에 기록 (`data.sql`에는 넣지 않음).
- 같은 (유저, 키) 20회 동시 → 기대: 201 1건 + 200 19건, 5xx 0건, DB 증가 1건.
- 서로 다른 1만 건 동시 (유저 1,000명 × 10건, 서로 다른 키, 동시 소켓 수와 클라이언트 timeout 명시) → 합격: 201 ≥ 9,500 그리고 DB 증가 = 201 수.
- 제목 없음/공백, 가격 없음(필드 생략), 카테고리 생략/없는 카테고리, 제목 101자(한글·이모지), 키 65자 → 400. 이모지 제목 100자, 이모지 내용 5000자 → 201.
- `X-User-Id` 없음/없는 유저 → 401.
- 내용 생략 → 201.
- `GET /posts/{id}` 헤더 없이 → 200, 없는 id → 404.
- import 검사: `user`·`category`에서 다른 도메인 참조 0건. `*.domain`에서 `org.springframework.web` 0건. `*.application`에서 `.presentation` 0건. `item.domain`에서 `user`·`category` 0건. `global`에서 도메인 참조 0건.
- 내용 생략으로 등록한 글을 `GET` → `content`가 null.
