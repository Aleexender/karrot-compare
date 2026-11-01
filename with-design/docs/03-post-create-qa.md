# 03. 게시글 등록·조회 API (`POST /posts`, `GET /posts/{id}`) — QA 보고서 (2026-10-29)

기준 문서: [01-post-create-requirements.md](01-post-create-requirements.md) (이하 R), [02-post-create-design.md](02-post-create-design.md) (이하 D), Main이 전달한 추가 확정 사항 (이하 X).

> **최신 판정: 회차 2 (DDD 리팩터링 후) — 통과.** 회귀는 없습니다. D-1은 회차 2에서 수정된 것을 확인했습니다. 자세한 내용은 문서 끝의 "회차 2" 절에 있습니다.
> 바로 아래 "결론"부터 "테스트 데이터 정리"까지는 회차 1(구현 직후) 기록입니다. 이 부분은 고치지 않았고, D-1 절에 상태 표기만 덧붙였습니다.

## 결론: 조건부 통과

- 완료 기준(R L27, D L88)은 **충족**했습니다. 유저 1,000명 × 10건, 서로 다른 키로 1만 건을 동시에 보냈고, 유효 측정 4회 모두 201이 10,000건, DB 증가도 10,000건으로 일치했습니다.
- 같은 (유저, 키)로 20회 동시 요청 3회, 엣지 케이스 curl 60여 건, `./gradlew clean test`(73건)도 모두 기대대로 동작했습니다.
- 결함은 1건이고 경미합니다(D-1). 이모지처럼 UTF-16 서로게이트 쌍으로 표현되는 문자는 2자로 세어집니다. 그래서 제목 51~100자, 내용 2,501~5,000자 범위의 이모지 입력이 400으로 거부됩니다. 핵심 흐름과 완료 기준에는 영향이 없어서 "조건부"로 판정했습니다.

## 실행 환경

| 항목 | 값 (실측) |
|---|---|
| 서버 | `./gradlew bootRun`, Java 25.0.4.1, Spring Boot 4.1.2, Tomcat 11.0.27, 포트 8081, PID 89449 |
| DB | Docker `karrot-mysql`(mysql:9.7, 9.7.4), localhost:3307, DB `karrot_with_design`. `max_connections=151`, `innodb_lock_wait_timeout=50`, `transaction_isolation=REPEATABLE-READ`, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`, `system_time_zone=UTC` |
| 클라이언트 호스트 | 같은 맥(darwin 24.6.0 arm64), loopback 사용 |
| FD 한도 (클라이언트 셸) | `ulimit -n` = 1048576 (soft), `ulimit -Hn` = unlimited. `launchctl limit maxfiles` = 256 / unlimited. `kern.maxfiles` = 491520, `kern.maxfilesperproc` = 245760 |
| FD 한도 (부하 JVM) | `UnixOperatingSystemMXBean.getMaxFileDescriptorCount()` = 1048576 |
| FD 한도 (서버 JVM) | `jcmd 89449 VM.info` → `NOFILE 1048576/infinity`, `MaxFDLimit=true` |
| 기타 커널 | `kern.ipc.somaxconn` = 128. 임시 포트 범위 49152–65535 (16,384개). `net.inet.tcp.msl` = 15000 |

## 체크리스트 결과

방법: 단위 = `./gradlew clean test`의 단위/WebMvc 테스트, 통합 = 같은 실행의 `@SpringBootTest` 테스트, API = bootRun 후 curl 또는 부하 도구 실행 + DB 조회, 정적 = 코드 읽기.

| # | 항목 | 출처 | 방법 | 결과 | 근거 |
|---|---|---|---|---|---|
| 1 | 정상 등록 → 201 + `Location: /posts/{id}` + 응답 본문 | R L5, D L30 | API, 통합 | 통과 | N1: `201`, `Location: /posts/9`, 본문 9개 필드. DB에 id=9 행 1건 |
| 2 | 응답 본문 필드(id, userId, categoryId, title, content, price, negotiable, status, createdAt) | D L47-52 | API | 통과 | N1 본문 `{"id":9,"userId":1,"categoryId":1,"title":"맥북 에어 M3","content":"거의 새것","price":1200000,"negotiable":true,"status":"ON_SALE","createdAt":"2026-10-29T03:44:15.270455"}` |
| 3 | 등록 시 항상 `ON_SALE`, 요청의 상태 값은 받지 않음 | R L6, D L26, L63 | API, 단위 | 통과 | G5: 본문에 `"status":"SOLD"`를 넣어도 201 + `ON_SALE`. DB `status=ON_SALE`. `ItemTest.startsOnSale` |
| 4 | 한 유저가 여러 게시글 작성 | R L4 | API | 통과 | 부하 측정에서 유저 1,000명이 각각 10건 → `GROUP BY user_id` 결과 1,000명 모두 10건 |
| 5 | 카테고리 선택(1 IT기기, 2 옷/패션, 3 가전제품) | R L7, D L67 | API, 정적 | 통과 | `data.sql:1-4`. DB `item_categories`에 1/2/3 존재. C5: categoryId=3 → 201 |
| 6 | 동시에 두 번 눌러도 1건만 저장: 같은 (유저, 키) 20회 동시 → 201 1 + 200 19, 5xx 0, DB +1 | R L14, D L87 | API, 통합 | 통과 | 3회 실행 모두 `{200=19, 201=1}`, 2xx 본문의 id 1종류, DB +1(31, 52, 70). 통합 `concurrentSameKeyCreatesOnlyOne` ×5 |
| 7 | 같은 (유저, 키) 재요청(본문 다름) → 200 + 최초 글, 새로 만들지 않음 | D L31 | API, 통합 | 통과 | N6: 제목·가격을 바꿔 재요청 → `200`, 본문이 N1과 동일. 해당 키 행 1건 유지 |
| 8 | 다른 유저가 같은 키 사용 → 각각 201 | D L60 (UNIQUE(user_id, key)) | API, 통합 | 통과 | K11: user 2 + `qa-n1` → 201. DB에 (1,qa-n1), (2,qa-n1) 2행 |
| 9 | 대소문자만 다른 키는 별개 (`ascii_bin`) | D L72 | API, 통합 | 통과 | K7/K8: `qa-case-abc`, `qa-case-ABC` → 각각 201(id 25, 26) |
| 10 | 키는 저장 성공 시에만 소비: 400 후 같은 키로 고쳐 보내면 201 | D L62 | API, 통합 | 통과 | K9 400(가격 누락) → K10 같은 키로 201(id 27) |
| 11 | 가격 누락 → 400 | R L15, D L32, L89 | API, 단위 | 통과 | P1: `400 application/problem+json`, items 증가 없음 |
| 12 | 가격 null / 음수(-1) → 400 | D L23, L32 | API, 단위 | 통과 | P2, P3: 400 |
| 13 | 가격 0(나눔) → 201 | D L23 | API, 통합 | 통과 | P4: 201, DB price=0 |
| 14 | 가격 Long 최댓값 → 201, Long 범위 초과 → 400 | D L23 (`Long`) | API, 통합/단위 | 통과 | P5: 201, DB `9223372036854775807`. P6: `9223372036854775808` → 400 |
| 15 | 제목 누락/null/빈 문자열/공백만 → 400 | R L16, D L21, L32, L89 | API, 단위 | 통과 | T1~T4: 400. T5(탭·개행만): 400 |
| 16 | 제목 101자 → 400, 100자 → 201 | D L21, L32, L89 | API, 단위/통합 | 통과 (한글 기준) | T6(한글 101자): 400. T7(한글 100자): 201, DB `CHAR_LENGTH(title)=100` |
| 17 | 제목 100자 이하 판정이 모든 문자에 대해 "자" 기준 | D L21 | API | **실패 (D-1)** | 이모지 50자 → 201, 51자 → 400, 100자 → 400 |
| 18 | 내용 생략 → 201 | R L17, D L22, L91 | API, 통합 | 통과 | D1: 201, DB `content IS NULL` |
| 19 | 내용 null / 빈 문자열 → 201 | D L22 | API, 통합(빈 문자열만) | 통과 | D2: 201, DB NULL. D3: 201, DB `''` |
| 20 | 내용 5000자 → 201, 5001자 → 400 | D L22, L32, L65 | API, 단위/통합 | 통과 (한글 기준) | D4(한글 5000자): 201, DB `CHAR_LENGTH=5000`, 15000바이트. D5(한글 5001자): 400 |
| 21 | 내용 5000자 이하 판정이 모든 문자에 대해 "자" 기준 | D L22, L65 | API | **실패 (D-1)** | 이모지 2,500자 → 201, 2,501자 → 400, 5,000자 → 400 |
| 22 | negotiable 생략 → false | D L24, L64 | API, 통합 | 통과 | G1: 201, 응답·DB 모두 false. G2(null): 201, false |
| 23 | categoryId 누락/null → 400 | D L25, L32, L89 | API, 단위 | 통과 | C1, C2: 400 |
| 24 | 존재하지 않는 카테고리 → 400 | D L25, L32, L89 | API, 통합 | 통과 | C3: 999 → `400 "Unknown categoryId: 999"`, items 증가 없음 |
| 25 | 필드 타입 오류: price 12.5 / "1000", title 123, negotiable "true" → 400 | D L32, X | API, 단위 | 통과 | P7, P8, T8, G3: 400. 추가로 T9(title true), P9(12.0), G4(negotiable 1), C4(categoryId "1")도 400 |
| 26 | JSON 형식 오류 → 400 | D L32 | API, 단위 | 통과 | J1(빈 본문), J2(닫히지 않은 객체), J3(배열): 400 |
| 27 | Idempotency-Key 없음/빈 문자열/공백만 → 400 | D L13, L32 | API, 단위 | 통과 | K1~K3: `400 "Required header 'Idempotency-Key' is not present."` |
| 28 | 키 65자 → 400, 64자 → 201 | D L13, L32, L89 | API, 단위/통합 | 통과 | K4: 400. K5: 201, DB 키 길이 64 |
| 29 | 키 비ASCII → 400 | D L13, X | API, 단위 | 통과 | K6: `키-123`(실제 Tomcat 경유) → 400 |
| 30 | X-User-Id 없음 → 401 | D L33, L90 | API, 단위 | 통과 | U1: `401 "Missing X-User-Id header"`. U2(빈 값)도 401 |
| 31 | X-User-Id 형식 오류 → 401 | D L33 | API, 단위 | 통과 | U3(`abc`), U5(Long 초과): 401 |
| 32 | 존재하지 않는 유저 → 401 | D L33, L90 | API, 통합 | 통과 | U4: 999999 → `401 "Unknown user: 999999"`, items 증가 없음 |
| 33 | 존재 확인 순서: 유저(401)를 카테고리(400)보다 먼저 | D L77 | API, 정적 | 통과 | U6: 없는 유저 + 없는 카테고리 → 401. `ItemCreator.java:26-29` |
| 34 | 잠금 대기 초과 → 503 | D L34 | API, 단위 | 통과 | 별도 세션이 `users id=1`에 `FOR UPDATE`를 잡은 상태에서 50건 요청 → 50건 모두 `503 application/problem+json`(약 50.1~50.2초, 1205). DB 증가 0. 두 번 반복 |
| 35 | DB 커넥션 대기 초과 → 503 | D L34 | API, 단위 | 통과 | 풀 50개가 잠금 대기로 모두 묶인 상태에서 curl 1건 → `503`, `time_total=35.018s`. 같은 조건에서 curl 10건 병렬 → 10건 모두 503, 35.00~35.02초. 서버 로그 `Connection is not available, request timed out after 35009ms (total=50, active=50 …)` |
| 36 | deadlock(1213) → 503 | D L34 | 단위 | 미검증 (API) | `ItemControllerWebMvcTest`의 mock 예외로만 확인. 실제 deadlock은 재현하지 않음 |
| 37 | 다른 무결성 오류(FK 1452, 길이 1406, 다른 UNIQUE)는 중복으로 취급하지 않음 | D L61 | 단위, 정적 | 통과 (단위) / API 미검증 | `ItemServiceTest.otherIntegrityViolationIsRethrown` 3건. 사전 존재 확인 때문에 API로는 1452를 낼 수 없어 API는 미검증 |
| 38 | `GET /posts/{id}` 헤더 없이 → 200, 등록 응답과 같은 본문 | D L38-39, L43, L92 | API, 통합 | 통과 | N2: 헤더 없이 `/posts/9` → 200, N1과 본문 동일(createdAt 마이크로초 포함). 부하로 만든 id 37153도 200 |
| 39 | 없는 id → 404 | D L44, L92 | API, 통합 | 통과 | N3: `404 application/problem+json` |
| 40 | id 형식 오류 → 400 | D L45 | API, 단위 | 통과 | N4(`abc`), N5(Long 초과): 400 |
| 41 | 에러 응답은 ProblemDetail | D L54 | API | 통과 | 관찰한 400/401/404/503 응답 모두 `Content-Type: application/problem+json`, `type/title/status/detail/instance` 형식 |
| 42 | createdAt: DB는 UTC 저장, 응답은 KST LocalDateTime | D L73, X | API, 정적 | 통과 | N1 응답 `03:44:15.270455`, DB `18:44:15.270455`(9시간 차이, 마이크로초 일치). `application.properties:18` |
| 43 | 의존 방향: `user` 패키지가 `item`/`category`를 import하지 않음 | R L9, D L68, L93 | 정적 | 통과 | `src/main/java/com/example/karrot/user`에서 `import com.example.karrot.(item\|category)` 검색 결과 0건 |
| 44 | `User`에 역방향 컬렉션 없음 | D L68 | 정적 | 통과 | `User.java:15-23` 필드는 id, nickname, createdAt뿐. `ItemCategory.java:12-16`도 id, name뿐 |
| 45 | `Item → User`, `Item → ItemCategory`는 단방향 `@ManyToOne(LAZY)` | D L68 | 정적 | 통과 | `Item.java:28-34` |
| 46 | 인터페이스는 Repository만 | R L10, D L70 | 정적 | 통과 | `src/main`의 interface는 `ItemRepository.java:6`, `UserRepository.java:5`, `ItemCategoryRepository.java:5` 3개뿐. Service/Controller는 클래스 |
| 47 | 패키지 `item`/`user`/`category` | D L69 | 정적 | 통과 | 디렉터리 구성 일치. 공통 설정 2개(`JacksonCoercionConfig`, `TransientDatabaseFailureHandler`)는 루트 패키지 |
| 48 | UNIQUE(user_id, idempotency_key), 이름 `uk_items_user_idempotency` | D L60 | 정적, API | 통과 | `schema.sql:24`. 개발 DB `SHOW CREATE TABLE items`에 `UNIQUE KEY uk_items_user_idempotency (user_id, idempotency_key)` |
| 49 | 키 컬럼 `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin` | D L72 | 정적, API | 통과 | `schema.sql:22`. 개발 DB DDL 일치 |
| 50 | `CHECK (price >= 0)`, `CHECK (status IN (…))` | D L63, L66 | 정적, 통합 | 통과 | `schema.sql:27-28`. 개발 DB `information_schema.CHECK_CONSTRAINTS`에 `ck_items_price`, `ck_items_status` 존재. `ItemsTableConstraintTest` 2건 |
| 51 | FK `items.user_id`, `items.category_id` | D L60-61 | 정적 | 통과 | `schema.sql:25-26`, 개발 DB DDL 일치 |
| 52 | 개발 DB에 변경된 스키마(CHECK, ascii_bin) 적용 | D L75 | API(DB 조회) | 통과 | 위 #49, #50의 실제 DDL |
| 53 | 가격 DTO는 `Long` + `@NotNull` + `@PositiveOrZero`, DB는 BIGINT | D L66 | 정적 | 통과 | `CreateItemRequest.java:12`, `schema.sql:18` |
| 54 | `@Enumerated(STRING)` | D L63 | 정적 | 통과 | `Item.java:47` |
| 55 | `@GeneratedValue(IDENTITY)`, `ddl-auto=none` | D L71, L74 | 정적 | 통과 | `Item.java:25`, `application.properties:15` |
| 56 | 충돌은 생성 트랜잭션 **바깥**에서 잡고 **새 readOnly 트랜잭션**에서 재조회 | D L61 | 정적, API | 통과 | `ItemService.java:21-33`(`@Transactional` 없음, `itemCreator.create` 호출을 try로 감쌈) → `ItemCreator.java:24`(`@Transactional`)의 롤백이 끝난 뒤 catch → `ItemReader.java:7`(클래스 `@Transactional(readOnly = true)`, 별도 빈이라 프록시 적용)의 `getByIdempotencyKey`. 충돌 판정은 1062 + 제약 이름 문자열(`ItemService.java:35-44`). 동시 20회 실행에서 5xx 0건 |
| 57 | 설정: Virtual Thread, Hikari 50, connection-timeout 35000, `max-connections=10500` | D L76 | 정적, API | 통과 | `application.properties:7,9,11,20`. 실행 중 서버 로그에 `total=50`, `timed out after 35009ms`. 요청 스레드 이름 `tomcat-handler-N`(가상 스레드). 1만 건 동시에 서버 쪽 ESTABLISHED 최대 9,719개를 수용 |
| 58 | 완료 기준: 서로 다른 1만 건 동시(1,000 × 10) → 201 ≥ 9,500 그리고 DB 증가 = 201 수 | R L27, D L88, X | API | 통과 | 아래 "완료 기준 측정" 참고. 유효 4회 모두 201=10,000, DB +10,000, id 집합 일치 |
| 59 | 처리량 T 실측 | D L76 | API | 측정함 | 1만 건 처리에 2.93~3.38초(약 2,960~3,410건/초). 설계 가정 315 TPS보다 높음 |

## 완료 기준 측정

**전제 (D L86)**
- 측정 전에 `DELETE FROM items`로 비웠습니다. 각 실행 직전에도 비우고, 실행 직전 `COUNT(*)`가 0인 것을 확인했습니다.
- 부하용 유저 1,000명(id 2~1001, nickname `qa-load-0001`~`qa-load-1000`)은 임시 SQL로 넣었습니다.

```sql
INSERT INTO users (nickname, created_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 1000)
SELECT CONCAT('qa-load-', LPAD(n,4,'0')), UTC_TIMESTAMP(6) FROM seq;
```

**부하 도구** (임시 파일 `/tmp/QaLoad.java`, 측정 후 삭제)
- Java 24 `HttpClient`, HTTP/1.1, 요청 1건당 가상 스레드 1개를 씁니다.
- 1만 개 스레드가 모두 준비되면 `CountDownLatch` 하나로 동시에 출발시킵니다.
- 키는 `qa-load-<userId>-<i>-<UUID>`로 모두 다르고, 유저마다 10건입니다. 본문은 `{"title":"부하 테스트","content":"burst","price":1000,"negotiable":false,"categoryId":1}`입니다.
- 클라이언트 timeout: 요청 120초, 연결 60초.
- 동시 소켓 수는 두 가지로 셌습니다. 하나는 클라이언트가 동시에 처리 중이던 요청의 최댓값(in-flight)입니다. 다른 하나는 300ms 간격 `netstat -an -p tcp` 샘플링으로 본 서버 쪽(local `.8081`) ESTABLISHED 소켓의 최댓값입니다.
- 결과 검증: 201 응답 본문의 id 목록과 DB `SELECT id FROM items` 목록을 `comm -3`으로 대조했습니다.
- 실행 명령: `java /tmp/QaLoad.java burst 2 1000 10 /tmp/qa-burst-201.txt`

| 실행 | 시각(KST) | 직전 TIME_WAIT | 요청 | 동시 in-flight 최대 | 서버 ESTABLISHED 최대 | 총 소요(출발→마지막 응답) | 201 | 200 | 4xx | 5xx | 연결 실패 | p50 / p95 / p99 / max (ms) | DB 증가 | 201 수 = DB 증가 | id 집합 불일치 | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 03:50:40 | (미측정) | 10,000 | 10,000 | 9,719 | 3,380ms | 10,000 | 0 | 0 | 0 | 0 | 1,801 / 3,149 / 3,306 / 3,375 | 10,000 | 일치 | 0 | 합격 |
| 2 | 03:51:09 | (미측정, 1회차 종료 약 25초 뒤 시작) | 10,000 | 8,856 | 6,317 | 4,205ms | 7,003 | 0 | 0 | 0 | 2,997 | 2,088 / 3,242 / 3,980 / 4,056 | 7,003 | 일치 | 0 | 무효 (클라이언트 포트 고갈, 아래 설명) |
| 3 | 03:51:34 | (미측정) | 10,000 | 9,573 | 9,455 | 3,306ms | 10,000 | 0 | 0 | 0 | 0 | 1,671 / 2,872 / 3,060 / 3,242 | 10,000 | 일치 | 0 | 합격 |
| 4 | 03:53:05 | 23 | 10,000 | 10,000 | 9,687 | 3,070ms | 10,000 | 0 | 0 | 0 | 0 | 1,482 / 2,751 / 2,909 / 3,019 | 10,000 | 일치 | 0 | 합격 |
| 5 | 03:53:50 | 6 | 10,000 | 10,000 | 9,410 | 2,930ms | 10,000 | 0 | 0 | 0 | 0 | 1,410 / 2,644 / 2,738 / 2,884 | 10,000 | 일치 | 0 | 합격 |

- 1회차 DB 확인 결과입니다.
  - `COUNT(*)=10000`, `COUNT(DISTINCT user_id)=1000`, `COUNT(DISTINCT user_id, idempotency_key)=10000`.
  - 유저별 10건씩 1,000명, 전부 `status=ON_SALE`.
  - `created_at` 범위는 UTC 18:50:40.78 ~ 18:50:44.03입니다.
- 1~5회차 동안 서버 로그에는 한 줄도 찍히지 않았습니다(WARN/ERROR, 풀 타임아웃, `Too many open files` 모두 0건).
- **2회차가 무효인 이유**
  - 실패 2,997건은 모두 클라이언트에서 난 `java.net.ConnectException: Can't assign requested address`(EADDRNOTAVAIL)입니다. 연결 단계에서 실패했으니 서버에 도달하지 않은 요청입니다.
  - 원인은 클라이언트 쪽 임시 포트 고갈로 추정합니다 [INFERENCE]. 1회차가 끝나고 약 25초 만에 시작했는데, 1회차의 1만 개 소켓이 TIME_WAIT(2 × MSL = 30초) 상태로 포트를 잡고 있었을 것입니다. 쓸 수 있는 포트는 16,384개입니다.
  - 그래서 4·5회차는 직전 TIME_WAIT가 23개, 6개인 것을 확인한 뒤 실행했습니다.
  - 2회차에서도 "DB 증가(7,003) = 201 수(7,003)", id 집합 불일치 0은 성립했습니다.
- **판정**: 유효 측정 4회(1, 3, 4, 5) 모두 201 = 10,000(기준 9,500 이상)이고 DB 증가 = 201 수 → 완료 기준 **통과**.

**같은 (유저, 키) 20회 동시** (D L87)
- 실행 명령: `java /tmp/QaLoad.java same 3 20 …`, 유저 3, 키 `qa-same-<UUID>`.

| 실행 | 상태 분포 | 2xx 본문 id 종류 | DB 증가 | 소요 |
|---|---|---|---|---|
| 1 | 201 ×1, 200 ×19 | 1 (id 31) | 1 | 75ms |
| 2 | 201 ×1, 200 ×19 | 1 (id 52) | 1 | 61ms |
| 3 | 201 ×1, 200 ×19 | 1 (id 70) | 1 | 58ms |

## 결함 (재현 명령 / 기대 / 실제)

### D-1. 제목·내용의 길이 제한이 문자 수가 아니라 UTF-16 code unit 수로 판정됨 (심각도: 낮음)

- **상태 (회차 2 갱신): 해결됨.** DDD 리팩터링 후 위의 재현 명령 2건이 모두 `201`을 반환했습니다(회차 2 E1, E2).
  - DB 확인 결과: 제목 `CHAR_LENGTH=100`(400바이트), 내용 `CHAR_LENGTH=5000`(20,000바이트).
  - `GET`으로 다시 읽은 값이 보낸 문자열과 같았습니다.
  - 경계도 확인했습니다. 이모지 제목은 100자 201, 101자 400이고, 이모지 내용은 5,000자 201, 5,001자 400입니다.
  - 수정 위치: `CreateItemRequest.java:13-14`(`@CodePointLength`), `Title.java:18`·`Content.java:17`(`codePointCount`).
  - 아래는 회차 1 기록 원문입니다.
- **기준**: D L21 "title … 100자 이하", D L22 / L65 "content … 5000자 이하".
- **판정 근거**: 이 판정은 "자"를 사용자가 보는 문자(유니코드 코드 포인트)로 해석한 것입니다. DB 컬럼 `VARCHAR(100)`(utf8mb4)도 코드 포인트로 셉니다. 설계 의도가 UTF-16 code unit이었다면 설계서에 그렇게 적어야 하고, 그 경우 이 항목은 결함이 아닙니다.
- **원인**: `CreateItemRequest.java:9-10`의 `@Size(max = 100)`, `@Size(max = 5000)`이 `String.length()`(UTF-16 code unit)를 셉니다. 그래서 BMP 밖 문자(이모지 등)는 1자가 2로 계산됩니다.
- **재현** (앱 기동 후):

```bash
# 제목: 이모지 100자 (100자 이하이므로 201이어야 함)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/posts \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'Idempotency-Key: qa-emoji-title-100' \
  --data-binary "{\"title\":\"$(python3 -c 'print("😀"*100)')\",\"price\":1,\"categoryId\":1}"
# 내용: 이모지 5000자 (5000자 이하이므로 201이어야 함)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8081/posts \
  -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'Idempotency-Key: qa-emoji-content-5000' \
  --data-binary "{\"title\":\"t\",\"content\":\"$(python3 -c 'print("😀"*5000)')\",\"price\":1,\"categoryId\":1}"
```

- **기대**: 둘 다 201.
- **실제**: 둘 다 `400 {"detail":"Validation failure",…}`. 경계 실측값은 아래와 같습니다.
  - 제목: 이모지 50자 → 201, 51자 → 400, 100자 → 400.
  - 내용: 이모지 2,500자 → 201(DB `CHAR_LENGTH=2500`, 10,000바이트), 2,501자 → 400, 5,000자 → 400.
  - 한글 등 BMP 문자는 100자 / 5000자 경계가 정확합니다(#16, #20).

## 테스트 커버리지 공백

`./gradlew clean test` 결과 (2026-10-29 03:43 KST): BUILD SUCCESSFUL, 73건 중 실패 0, 오류 0, 건너뜀 0.

| 테스트 클래스 | 건수 |
|---|---|
| ItemControllerWebMvcTest | 37 |
| ItemServiceTest | 4 |
| ItemTest | 6 |
| ItemsTableConstraintTest | 2 |
| PostApiIntegrationTest | 18 |
| PostConcurrencyIntegrationTest | 6 |

통합 테스트는 `karrot_with_design_test` DB를 쓰고(`src/test/resources/config/application.properties:3`), 매 컨텍스트 시작 때 테이블을 DROP 후 재생성합니다.

자동 테스트가 덮지 못한 체크리스트 항목입니다(이번 QA에서 API로 직접 확인한 결과를 괄호에 적음).

| 체크리스트 # | 공백 |
|---|---|
| 58, 59 | 완료 기준(1만 건 동시, 1,000 × 10) 자동 테스트 없음. 동시성 테스트는 같은 키 20회와 다른 키 20회뿐 (API로 측정, 통과) |
| 34, 35 | 503은 WebMvc mock 예외로만 확인. 실제 잠금 대기·풀 고갈 경로의 통합 테스트 없음 (API로 확인, 통과) |
| 36 | 실제 deadlock 1213 → 503은 어디서도 재현되지 않음 (미검증) |
| 43, 44, 46 | 의존 방향·인터페이스 규칙의 자동 검사(ArchUnit 등) 없음. 코드가 바뀌면 다시 눈으로 확인해야 함 (정적으로 확인, 통과) |
| 17, 21 | 서로게이트 쌍 문자의 길이 경계 테스트 없음 (D-1) |
| 3 | 요청에 `status`를 넣어도 무시되는지 API 수준 테스트 없음. 도메인 `ItemTest.startsOnSale`만 있음 (API로 확인, 통과) |
| 19 | 내용 명시적 `null` 테스트 없음. 생략과 빈 문자열만 있음 (API로 확인, 통과) |
| 22 | negotiable 명시적 `null` 테스트 없음 (API로 확인, 통과) |
| 29 | 비ASCII 키는 MockMvc로만 확인. 실제 Tomcat(ISO-8859-1 헤더 디코딩) 경로 테스트 없음 (API로 확인, 통과) |
| 42 | DB UTC 저장, 응답 KST 변환을 검증하는 테스트 없음 (API로 확인, 통과) |
| 52 | 개발 DB의 실제 DDL이 `schema.sql`과 같은지는 테스트 범위 밖. 테스트 DB는 매번 재생성 (DB 조회로 확인, 통과) |
| 57 | 설정값(풀 50, timeout 35s, max-connections 10500)을 단언하는 테스트 없음. 통합 테스트가 운영 설정으로 돌기는 함 |

## 개선 제안 (기준 밖)

판정에 쓰지 않은 관찰입니다.

1. **잠금 대기 시간이 커넥션 대기 시간보다 김**: `innodb_lock_wait_timeout=50`이 Hikari `connection-timeout=35000`보다 깁니다. 그래서 잠금에 막힌 요청은 50초 동안 커넥션을 붙잡고 있고, 뒤에 온 요청은 35초 뒤 503을 받습니다(#34, #35 실측). 잠금 대기를 커넥션 대기보다 짧게 둘지는 설계에서 정할 일입니다.
2. **조회도 쓰기와 같은 풀을 씀**: 풀 50개가 모두 잠금 대기 중일 때 `GET /posts/9`가 8.4초 기다린 뒤 200을 받았습니다. 풀이 비면 조회도 막힙니다. 인기 게시글 12만 조회(R L25)를 설계할 때 고려할 점입니다.
3. **헤더 오류가 여러 개일 때 우선순위가 정해져 있지 않음**: X-User-Id 없음 + Idempotency-Key 없음 → 400(U7), X-User-Id 없음 + 제목 없음 → 400(U8). 즉 인증(401)보다 입력 검증(400)이 먼저 판정됩니다. 설계서에 우선순위가 없어 결함으로 보지 않았습니다. 정할 필요가 있으면 설계서에 명시하는 것이 좋습니다.
4. **알 수 없는 필드는 무시됨**: J4에서 `"foo":"bar"`를 넣어도 201이었습니다.
5. **`Content-Type: text/plain`이면 415**(J5): 설계서 상태 코드 표에 없는 응답입니다. 필요하면 표에 추가하는 것이 좋습니다.
6. **재조회 실패 시 500 가능성** [INFERENCE]: `ItemReader.getByIdempotencyKey`는 행이 없으면 `NoSuchElementException`을 던집니다(`ItemReader.java:25`). 충돌 직후 상대 행이 지워지는 경우 등에서는 500이 됩니다. 현재 삭제 API가 없어서 실제로 생기지는 않습니다.
7. **측정 도구 관찰**: 풀 대기 초과 실험(60건 동시, 잠금 유지)에서 서버는 10건을 35.0초에 503으로 판정했습니다(로그 시각 03:46:49.9). 그런데 Java `HttpClient`는 60건 모두를 약 50.15초에 받았습니다. 같은 조건에서 curl은 35.0초에 받았으므로 클라이언트 라이브러리 쪽 현상으로 봅니다(원인 미확인). 상태 코드 판정에는 영향이 없고, 1만 건 측정에서는 503이 0건이라 해당하지 않습니다.
8. **단일 호스트 부하의 한계**: 임시 포트 16,384개와 TIME_WAIT 30초 때문에, 한 맥에서 1만 건 측정을 30초 안에 반복하면 클라이언트 연결이 실패합니다(2회차). 반복 측정 절차에 "TIME_WAIT 해소 확인"을 넣는 것이 좋습니다. `kern.ipc.somaxconn=128`(백로그 상한)에서도 1만 동시 연결이 실패 없이 수용됐습니다.

## 테스트 데이터 정리 (개발 DB `karrot_with_design`)

- **QA 시작 시 삭제한 데이터**: developer가 남긴 items id 1, 3, 4, 5, 6, 8 (설계서 전제 "측정 전 items 비우기"에 따라 삭제, 복원하지 않음).
- **QA 중 넣은 데이터**:
  - 부하용 users 1,000명(id 2~1001, `qa-load-*`).
  - items: 엣지 케이스·동시성·부하 실행으로 생성. 마지막 부하 실행의 최소 id는 37153.
- **QA 종료 시 삭제한 데이터**: items 전부, users `nickname LIKE 'qa-load-%'` 1,000명.
- **남은 상태 (확인함)**:
  - `items` 0행.
  - `users` 1행(id=1, `design-check-user`, developer 데이터 그대로).
  - `item_categories` 3행(1, 2, 3).
  - `items`의 AUTO_INCREMENT는 되돌리지 않았으므로 다음 id는 1부터 시작하지 않습니다.
- **테스트 DB `karrot_with_design_test`**: `./gradlew clean test`가 사용했습니다. 매 실행 때 재생성되므로 손대지 않았습니다.
- **임시 파일**: `/tmp/qa-curl.sh`, `/tmp/QaLoad.java`, `/tmp/qa-*.txt`, `/tmp/qa-lock.log`는 삭제했습니다.
- **앱**: bootRun 프로세스를 종료했습니다(PID 89449 없음, 8081 LISTEN 없음 확인).

---

# 회차 2 (DDD 리팩터링 후, 2026-10-29 16:30~16:38 KST)

- 대상: DDD 4계층(presentation → application → domain)으로 리팩터링한 코드입니다. 동작은 바꾸지 않았고, D-1만 수정했습니다(제목·내용 길이를 코드 포인트로 셈).
- 기준 문서: 갱신된 설계서 D(결정 사항 L60-81, 패키지 구조 L83-121, 검증 계획 L129-138)와 architect 확정안(`local://ddd-review.md`)입니다. **회차 2 표의 D 줄 번호는 갱신된 설계서 기준**이라서 회차 1 표의 줄 번호와 다릅니다.
- 회차 1과 같은 엣지 케이스 ID(N/P/T/D/G/C/J/K/U)로 다시 실행했습니다. 회차 1 스크립트는 삭제됐기 때문에 보고서에 적힌 설명대로 다시 만들었습니다. 새로 추가한 케이스는 E(이모지 경계), T10, L(잠금 대기), V(조회 시 VO 검증)입니다.

## 결론: 통과

- **회귀 없음**: 회차 1 체크리스트 전부를 다시 실행했습니다. 그 결과 상태 코드, `Content-Type: application/problem+json`, detail 문자열이 회차 1에 기록된 값과 모두 같았습니다.
  - 차이가 3개 있었지만 모두 원인을 확인했고 결함이 아닙니다. 아래 "회차 1 대비 비교" 표에 정리했습니다.
- **D-1 해결**: 회차 1의 재현 curl 2건이 이제 201을 반환합니다. 이모지 기준 경계는 제목 100/101자, 내용 5,000/5,001자로 정확합니다.
- **완료 기준 통과**: 1만 건 측정은 유효 3회였고, 3회 모두 201 = 10,000, DB 증가 = 10,000, id 집합 불일치 0이었습니다.
- **같은 키 20회 동시**: 3회 모두 201 ×1, 200 ×19, DB +1이었습니다.
- **503(잠금 대기)**: 패키지를 옮긴 뒤에도 동작합니다.
- **정적 검토**: 패키지 배치, import 검사 5종, ID 참조, VO 컬럼 매핑, 트랜잭션 경계가 모두 설계와 일치합니다.
- **자동 테스트**: `./gradlew clean test` 92건이 모두 통과했습니다.
- **미검증**: #35(풀 대기 초과 503)는 이번 회차에 다시 실행하지 않았습니다. #36(실제 deadlock)과 #37의 API 경로도 미검증입니다.

## 실행 환경 (회차 2)

| 항목 | 값 (실측) |
|---|---|
| 서버 | `./gradlew bootRun`, Java 25.0.4.1, Spring Boot 4.1.2, Spring Framework 7.0.10(스택 트레이스의 jar 이름), Tomcat 11.0.27, Hibernate ORM 7.4.13.Final, 포트 8081. 1차 기동 PID 73793(gradle 73764), V 확인용 2차 기동 PID 76931(gradle 76902) |
| DB | `karrot-mysql` 9.7.4, `max_connections=151`, `innodb_lock_wait_timeout=50`, `REPEATABLE-READ`, `sql_mode`에 `STRICT_TRANS_TABLES` 포함 |
| 클라이언트 | 같은 맥, loopback. 부하 도구는 임시 파일 `/tmp/Qa2Load.java`(회차 1 도구와 같은 방식: `HttpClient` HTTP/1.1, 요청당 가상 스레드, `CountDownLatch` 동시 출발, 요청 timeout 120초·연결 timeout 60초, 300ms 간격 `netstat` 샘플링) |

## 체크리스트 결과 (회차 2)

방법 표기는 회차 1과 같습니다. 엣지 케이스 ID는 이번 회차 curl 실행 ID이고, 회차 1의 같은 ID와 같은 입력입니다(차이가 있으면 표에 적음). "db+N"은 요청 직전과 직후 `SELECT COUNT(*) FROM items`의 차이입니다.

| # | 항목 | 출처 | 방법 | 결과 | 근거 |
|---|---|---|---|---|---|
| 1 | 정상 등록 → 201 + Location + 본문 | R L5, D L30 | API, 통합 | 통과 | N1: `201 application/json`, `Location: /posts/47157`, db+1 |
| 2 | 응답 본문 9개 필드 | D L47-52 | API | 통과 | N1 `{"id":47157,"userId":1,"categoryId":1,"title":"맥북 에어 M3","content":"거의 새것","price":1200000,"negotiable":true,"status":"ON_SALE","createdAt":"2026-10-29T16:32:32.088198"}` |
| 3 | 등록 시 항상 ON_SALE, 요청의 status 무시 | R L6, D L63 | API, 단위 | 통과 | G5(`"status":"SOLD"`): 201, 응답과 DB 모두 `ON_SALE`. `ItemTest` "새 게시글은 항상 판매중(ON_SALE) 상태로 시작한다" |
| 4 | 한 유저가 여러 게시글 | R L4 | API | 통과 | 1만 건 측정 3회 모두 `GROUP BY user_id` 결과 1,000명이 각각 10건 |
| 5 | 카테고리 1/2/3 선택 | R L7, D L67 | API, 정적 | 통과 | C5: categoryId=3 → 201, DB `category_id=3`. `data.sql:1-4`, DB `item_categories` 3행 |
| 6 | 같은 (유저, 키) 20회 동시 → 201 1 + 200 19, 5xx 0, DB +1 | R L14, D L131 | API, 통합 | 통과 | 아래 "같은 키 20회 동시" 3회. 통합 repetition 5회 통과 |
| 7 | 같은 키 재요청(본문 다름) → 200 + 최초 글 | D L31 | API, 통합 | 통과 | N6: 200, 본문이 N1과 바이트 단위로 동일(`cmp`), db+0 |
| 8 | 다른 유저가 같은 키 → 각각 201 | D L60 | API, 통합 | 통과 | K11: user 1025 + `qa-n1` → 201(id 47181). DB에 (1,qa-n1), (1025,qa-n1) 두 행 |
| 9 | 대소문자만 다른 키는 별개 | D L76 | API, 통합 | 통과 | K7/K8 → 201(id 47178, 47179) |
| 10 | 키는 저장 성공 시에만 소비 | D L62 | API, 통합 | 통과 | K9 400(db+0) → K10 같은 키로 201(id 47180). DB에 `qa-k9`는 1행 |
| 11 | 가격 누락 → 400 | R L15, D L32 | API, 단위 | 통과 | P1: `400 "Validation failure"`, db+0 |
| 12 | 가격 null/-1 → 400 | D L23 | API, 단위 | 통과 | P2, P3: `400 "Validation failure"` |
| 13 | 가격 0 → 201 | D L23 | API, 통합 | 통과 | P4: 201, DB price=0 |
| 14 | Long 최댓값 201, 범위 초과 400 | D L23 | API, 통합/단위 | 통과 | P5: 201, DB `9223372036854775807`. P6: `400 "Failed to read request"` |
| 15 | 제목 누락/null/빈 문자열/공백만 → 400 | R L16, D L21 | API, 단위 | 통과 | T1~T5: `400 "Validation failure"`. 추가 T10(전각 공백 U+3000만): 400 |
| 16 | 한글 제목 101자 400, 100자 201 | D L21 | API, 통합 | 통과 | T6: 400. T7: 201, DB `CHAR_LENGTH=100`(300바이트) |
| 17 | 제목 길이를 코드 포인트로 판정(이모지) | D L21, L133 | API, 단위/통합 | **통과 (D-1 해결)** | E1(이모지 100자, 회차 1 재현 명령과 같은 입력): **201**(id 47162), DB `CHAR_LENGTH=100`, 400바이트, GET 값이 보낸 문자열과 같음. E3(50자): 201. E4(51자): **201**(회차 1에서는 400). E5(101자): `400 "Validation failure"`, db+0 |
| 18 | 내용 생략 → 201 | R L17, D L135 | API, 통합 | 통과 | D1: 201, DB `content IS NULL` |
| 19 | 내용 null/빈 문자열 → 201 | D L22, L65 | API, 통합 | 통과 | D2: 201, DB NULL. D3: 201, DB `''`(CHAR_LENGTH 0) |
| 20 | 한글 내용 5000자 201, 5001자 400 | D L22 | API, 통합 | 통과 | D4: 201, DB `CHAR_LENGTH=5000`, 15,000바이트. D5: 400 |
| 21 | 내용 길이를 코드 포인트로 판정(이모지) | D L22, L65, L133 | API, 단위/통합 | **통과 (D-1 해결)** | E2(이모지 5000자, 회차 1 재현 명령과 같은 입력): **201**(id 47163), DB `CHAR_LENGTH=5000`, 20,000바이트, GET 값이 보낸 문자열과 같음. E6(2,500자): 201. E7(2,501자): **201**(회차 1에서는 400). E8(5,001자): `400 "Validation failure"`, db+0 |
| 22 | negotiable 생략/null → false | D L64 | API, 통합 | 통과 | G1, G2: 201, 응답과 DB 모두 false |
| 23 | categoryId 누락/null → 400 | D L25 | API, 단위 | 통과 | C1, C2: `400 "Validation failure"` |
| 24 | 없는 카테고리 → 400 | D L25 | API, 통합 | 통과 | C3: `400 "Unknown categoryId: 999"`, db+0 |
| 25 | 필드 타입 오류 → 400 | D L32 | API, 단위 | 통과 | P7, P8, P9, T8, T9, G3, G4, C4: 모두 `400 "Failed to read request"` |
| 26 | JSON 형식 오류 → 400 | D L32 | API, 단위 | 통과 | J1, J2, J3: `400 "Failed to read request"` |
| 27 | 키 없음/빈 값/공백만 → 400 | D L13, L32 | API, 단위 | 통과 | K1(헤더 없음): `400 "Required header 'Idempotency-Key' is not present."`. K2(`-H 'Idempotency-Key;'`로 빈 값을 실제로 보냄): `400 "Validation failure"`. K3(`-H 'Idempotency-Key:    '`): `400 "Required header … not present."`. 공백만(`"   "`, 탭) 값을 `nc`로 직접 보냄: 둘 다 `400 "Validation failure"`. 회차 1과 detail이 다른 이유는 아래 비교표 참고 |
| 28 | 키 65자 400, 64자 201 | D L13 | API, 단위/통합 | 통과 | K4: 400. K5: 201, DB 키 64자 |
| 29 | 비ASCII 키 → 400 | D L13 | API, 단위 | 통과 | K6(`키-123`, Tomcat 경유): `400 "Validation failure"` |
| 30 | X-User-Id 없음/빈 값 → 401 | D L33, L134 | API, 단위 | 통과 | U1: `401 "Missing X-User-Id header"`. U2(`-H 'X-User-Id;'`): `401 "Malformed X-User-Id header: "` |
| 31 | X-User-Id 형식 오류 → 401 | D L33 | API, 단위 | 통과 | U3: `401 "Malformed X-User-Id header: abc"`. U5: `401 "Malformed X-User-Id header: 99999999999999999999"` |
| 32 | 없는 유저 → 401 | D L33, L134 | API, 통합 | 통과 | U4: `401 "Unknown user: 999999"`, db+0 |
| 33 | 판정 순서: 유저(401)가 카테고리(400)보다 먼저 | D L81 | API, 정적 | 통과 | U6(없는 유저 + 없는 카테고리): `401 "Unknown user: 999999"`. `ItemCreator.java:31-36`에서 user → category 순서로 `existsById`를 호출하고, VO는 그 뒤 `:37-39`에서 생성 |
| 34 | 잠금 대기 초과 → 503 (패키지 이동 후) | D L34 | API, 단위 | 통과 | L1~L5: 별도 세션이 `SELECT id FROM users WHERE id=1 FOR UPDATE`로 잠금을 잡고 `SLEEP(65)`하는 동안, user 1로 POST 5건을 병렬로 보냄. 결과: 5건 모두 `503 application/problem+json`, `time_total` 50.153~50.153초, detail `"The database is temporarily busy. Retry the request later."`. 서버 로그 `ErrorCode: 1205, SQLState: 40001` 5건(16:35:13.855). DB의 `qa-lock-%` 행 0, items 수 변화 없음(10000→10000) |
| 35 | 커넥션 대기 초과 → 503 | D L34 | 단위 | 미검증 (API, 회차 2) | 이번 회차에서는 API로 재현하지 않음. WebMvc "커넥션 풀 대기 초과" 통과. 매핑 코드(`TransientDatabaseFailureHandler.java:20-41`)는 회차 1과 같은 로직이고 패키지만 옮겨졌으며, 같은 핸들러를 #34에서 실제로 탄 것을 확인함 |
| 36 | deadlock → 503 | D L34 | 단위 | 미검증 (API) | WebMvc "deadlock(1213)", "커밋 중 deadlock(1213)" 통과 |
| 37 | 다른 무결성 오류는 중복으로 취급하지 않음 | D L61 | 단위, 정적 | 통과 (단위) / API 미검증 | `ItemServiceTest` 1062(다른 키)/1452/1406 3건 통과. 판정 코드 `ItemService.java:36-45` |
| 38 | GET 헤더 없이 → 200, 등록 응답과 같은 본문 | D L38-43, L136 | API, 통합 | 통과 | N2: `GET /posts/47157` 헤더 없이 200, N1 본문과 바이트 단위로 동일(`cmp`) |
| 39 | 없는 id → 404 | D L44, L136 | API, 통합 | 통과 | N3: `404 "Item not found: 999999999"` |
| 40 | id 형식 오류 → 400 | D L45 | API, 단위 | 통과 | N4: `400 "Failed to convert 'id' with value: 'abc'"`. N5: Long 초과 400 |
| 41 | 에러 응답은 ProblemDetail | D L54 | API | 통과 | 관찰한 400/401/404/415/503 응답 모두 `Content-Type: application/problem+json`, 본문 키는 `detail/instance/status/title`. `type` 키는 어떤 응답에도 없음(RFC 9457 §3.1.1: `type`이 없으면 `"about:blank"`로 간주). 회차 1 표기와의 차이는 아래 비교표 참고 |
| 42 | createdAt: DB UTC, 응답 KST | D L77 | API | 통과 | N1 응답 `16:32:32.088198`, DB `07:32:32.088198`(9시간 차이, 마이크로초 일치) |
| 44 | 역방향 컬렉션 없음 | D L68 | 정적 | 통과 | `User.java:15-23`(id, nickname, createdAt), `ItemCategory.java:12-16`(id, name) |
| 46 | 인터페이스는 Repository만 | R L10, D L70 | 정적 | 통과 | `src/main`의 interface는 `ItemRepository.java:6`, `UserRepository.java:5`, `ItemCategoryRepository.java:5` 3개뿐. VO 3개와 `RegisterItemCommand`, `ItemRegistration`, DTO 2개는 record. `ItemStatus`는 enum. 나머지(Controller, 핸들러 2개, Service 3개, 예외 3개, 설정, Entity 3개, `KarrotApplication`)는 class |
| 48~52 | UNIQUE·ascii_bin·CHECK·FK·개발 DB 스키마 | D L60, L63, L66, L76, L79 | 정적, API(DB 조회) | 통과 | `schema.sql:22-28`. 개발 DB `SHOW CREATE TABLE items`에 `uk_items_user_idempotency (user_id, idempotency_key)`, `idempotency_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin`, `fk_items_user`, `fk_items_category`가 있음. `information_schema.CHECK_CONSTRAINTS`에 `ck_items_price (price >= 0)`, `ck_items_status (status in ('ON_SALE','SOLD','STOPPED'))`가 있음. `ItemsTableConstraintTest` 2건 통과 |
| 53 | 가격 DTO `Long` + `@NotNull @PositiveOrZero` | D L66 | 정적 | 통과 | `CreateItemRequest.java:16`, `schema.sql:18` |
| 54 | `@Enumerated(STRING)` | D L63 | 정적 | 통과 | `Item.java:43` |
| 55 | IDENTITY, `ddl-auto=none` | D L75, L78 | 정적 | 통과 | `Item.java:21`, `application.properties:15` |
| 56 | 충돌 처리 트랜잭션 경계 유지(생성 트랜잭션 밖에서 잡고 새 readOnly 트랜잭션에서 재조회) | D L61 | 정적, API | 통과 | `ItemService.java:7-8`(클래스·메서드에 `@Transactional` 없음, `src/main`에서 `@Transactional`은 `ItemCreator.java:29`와 `ItemReader.java:9` 두 곳뿐). `ItemService.java:24-33`: `itemCreator.create`(별도 빈, `@Transactional`)를 try로 감싸고, 그 트랜잭션이 롤백된 뒤 catch → `ItemReader.getByIdempotencyKey`(별도 빈, 클래스 `@Transactional(readOnly = true)`). 1062 + 제약 이름 판정은 `:36-45`. API: 같은 키 20회 동시 3회에서 5xx 0, 서버 로그에 1062 WARN만 있고 ERROR 없음 |
| 57 | Virtual Thread, Hikari 50, timeout 35s, max-connections 10500 | D L80 | 정적, API | 통과 | `application.properties:7,9,11,20`. 요청 스레드 이름 `tomcat-handler-N`. 1만 건에서 서버 쪽 ESTABLISHED 최대 10,000개 수용 |
| 58 | 완료 기준: 1만 건(1,000 × 10) → 201 ≥ 9,500 그리고 DB 증가 = 201 수 | R L27, D L132 | API | 통과 | 아래 "완료 기준 측정 (회차 2)". 유효 3회 모두 10,000 / 10,000 |
| 59 | 처리량 T | D L80 | API | 측정함 | 1만 건 처리에 3.09~3.19초(약 3,140~3,240건/초). 회차 1(2.93~3.38초)과 같은 범위 |
| 60 | 패키지 구조가 설계서 "패키지 구조" 절과 일치 | D L69, L83-109 | 정적 | 통과 | `src/main/java` 25개 클래스 배치가 설계 트리와 1:1로 같음. `global/config/JacksonCoercionConfig`, `global/error/TransientDatabaseFailureHandler`, `item/presentation/{ItemController, ItemExceptionHandler}`, `item/presentation/dto/{CreateItemRequest, ItemResponse}`, `item/application/{ItemService, ItemCreator, ItemReader, ItemRegistration, RegisterItemCommand, ItemNotFoundException, UnknownCategoryException, UnauthenticatedUserException}`, `item/domain/{Item, ItemStatus, Title, Content, Price, ItemRepository}`, `category/domain/{ItemCategory, ItemCategoryRepository}`, `user/domain/{User, UserRepository}`, 루트 `KarrotApplication`. 설계에 없는 클래스나 infrastructure 패키지는 없음. 테스트도 `item/{domain,application,presentation}`와 `item` 루트(통합)로 같은 구조 |
| 61 | import 검사 5종 (모두 0건이어야 함) | R L9, D L117-121, L137 | 정적 | 통과 | 검색 도구로 직접 실행. ① `user/`에서 `^import com\.example\.karrot\.(item\|category)` 0건, `category/`에서 `^import com\.example\.karrot\.(item\|user)` 0건. ② `src/main/java/**/domain/**`에서 `org\.springframework\.web` 0건(같은 glob으로 `^import`를 검색하면 domain 파일 9개(import가 없는 `ItemStatus` 제외)가 걸리는 것을 대조로 확인). ③ `**/application/**`에서 `\.presentation` 0건. ④ `item/domain`에서 `com\.example\.karrot\.(user\|category)` 0건(import가 아닌 FQN 참조 포함). ⑤ `global/`에서 `com\.example\.karrot\.(item\|user\|category)` 0건. 전체 내부 import 목록을 보면 presentation → application/domain(`ItemController.java:3-9`, `ItemExceptionHandler.java:3-5`, `CreateItemRequest.java:3-5`, `ItemResponse.java:3-4`), application → item.domain + `UserRepository` + `ItemCategoryRepository`(`ItemCreator.java:3-9`)로, 설계의 의존 규칙(D L118-121)과 일치 |
| 62 | Item은 다른 Aggregate를 ID로 참조하고 객체 연관이 없음, DB FK 유지 (회차 1 #45 대체) | D L68 | 정적, API | 통과 | `Item.java:24-28`: `@Column(name="user_id") Long userId`, `@Column(name="category_id") Long categoryId`. `item/domain`, `user`, `category`에서 `ManyToOne\|OneToMany\|OneToOne\|ManyToMany\|JoinColumn` 0건. FK는 `schema.sql:25-26`과 개발 DB DDL에 그대로 있음 |
| 63 | VO 매핑 컬럼명 | D L113-115 | 정적, API | 통과 | `Title.java:9` `@Column(name = "title", nullable = false, length = Title.MAX_LENGTH)`, `Content.java:11` `@Column(name = "content")`, `Price.java:7` `@Column(name = "price", nullable = false)`. `Item.java:30-38`은 `@Embedded` 3개. API로 확인: N1 행이 DB의 `title`/`content`/`price` 컬럼에 각각 저장되고(CHAR_LENGTH 8/5, price 1200000), GET으로 같은 값이 돌아옴 |
| 64 | VO 불변식(record, compact 생성자, 정규화 없는 코드 포인트) | D L71, L113-115 | 정적, 단위 | 통과 | `Title.java:13-21`(null → NPE, `isBlank` → IAE, `codePointCount > 100` → IAE), `Content.java:15-20`(null → NPE, `codePointCount > 5000` → IAE, `""` 허용), `Price.java:9-13`(음수 → IAE). TitleTest 11건, ContentTest 4건, PriceTest 2건 통과(`e`+U+0301 50쌍 허용, 51쌍 거부 포함) |
| 65 | 검증 역할: DTO가 1차 관문, 숫자는 VO 상수 공유, VO 예외는 HTTP로 매핑하지 않음 | D L72 | 정적, API | 통과 | `CreateItemRequest.java:13-14` `@CodePointLength(max = Title.MAX_LENGTH)`, `@CodePointLength(max = Content.MAX_LENGTH)`(`normalizationStrategy`를 지정하지 않음 = 기본값). 길이 초과(E5, E8, T6, D5)는 VO까지 가지 않고 DTO에서 `400 "Validation failure"`로 막힘(500 아님, db+0). VO 예외용 핸들러 없음(`ItemExceptionHandler.java:14-27`에 3종만 있음) |
| 66 | 예외 매핑: 업무 예외는 HTTP를 모르는 RuntimeException, `ItemExceptionHandler`가 401/400/404, `TransientDatabaseFailureHandler`가 503 | D L73 | 정적, API | 통과 | `UnauthenticatedUserException.java:3`, `UnknownCategoryException.java:3`, `ItemNotFoundException.java:3`이 모두 `extends RuntimeException`. `ItemExceptionHandler.java:14-27`, `TransientDatabaseFailureHandler.java:20-27`. API: U1~U6 401, C3 400, N3 404, L1~L5 503. detail 문자열은 회차 1과 같음 |
| 67 | application 입출력: `RegisterItemCommand` 입력, domain `Item` 출력 | D L74 | 정적 | 통과 | `ItemService.java:24` `register(RegisterItemCommand)`, `ItemCreator.java:30` `Item create(...)`, `ItemReader.java:18,23`은 `Item`을 반환, `ItemRegistration.java:5` `record(Item item, boolean created)`. 변환은 `ItemController.java:43-45, 54`에서 함 |
| 68 | 존재 확인은 `existsById`로, VO는 그 뒤에 생성 | D L81 | 정적, API | 통과 | `ItemCreator.java:31,34`(`existsById`) → `:37-39`(VO 생성). U6 → 401 |
| 69 | 내용 생략으로 등록한 글을 GET → content null | D L138 | API, 통합 | 통과 | D1 → D1G: `GET /posts/47168` 200, `"content":null`. D2(null) → D2G도 null. D3(`""`) → D3G `"content":""`로 NULL과 `''`가 구분됨. 통합 "내용을 생략해 등록한 게시글을 조회하면 200이고 content는 null" 통과 |
| 70 | DB에 수동으로 넣은 규칙 위반 행은 GET 시 500 (VO 검증이 조회 때도 돎) | D L71 | API | 통과 | V1: 제목 `'   '`인 행을 SQL로 직접 넣음(id 77242) → `GET /posts/77242` → `500`. 서버 로그의 근본 원인은 `java.lang.IllegalArgumentException: title must not be blank at Title.java:16`(`JpaSystemException: Could not instantiate entity …Title`로 감싸짐). 확인 직후 행 삭제. 응답 형식은 개선 제안 2 참고 |

회차 1의 #43(user import 검사)은 #61의 ①로, #45(`@ManyToOne`)는 #62로, #47(패키지)은 #60으로 대체했습니다. 설계가 바뀌었기 때문입니다(D L68-69, L137).

### 회차 1 대비 비교 (회귀 판정)

| 항목 | 회차 1 기록 | 회차 2 관찰 | 판정 |
|---|---|---|---|
| 모든 엣지 케이스의 상태 코드 | #1~#42 표 | 같은 ID 전부 같은 상태 코드. 예외는 D-1 대상인 E4/E7(400 → 201)뿐 | 회귀 없음 |
| detail 문자열 | `Validation failure`, `Unknown categoryId: 999`, `Required header 'Idempotency-Key' is not present.`, `Missing X-User-Id header`, `Unknown user: 999999` | 같음. 이번에 추가로 기록한 문자열은 `Item not found: N`, `Malformed X-User-Id header: …`, `Failed to read request`, `Failed to convert 'id' with value: '…'`, 415 `Content-Type 'text/plain;charset=UTF-8' is not supported.`, 503 `The database is temporarily busy. Retry the request later.` | 회귀 없음 |
| K2/K3 detail | K1~K3 모두 `Required header … not present.` | K2(`-H 'Idempotency-Key;'`)는 `Validation failure` | 회귀 아님. curl은 `-H 'Name: '`처럼 값이 비었거나 공백만 있으면 **헤더를 아예 보내지 않음**(`--trace-ascii`로 확인: `Idempotency-Key: `, `Idempotency-Key:`, `Idempotency-Key:    ` 세 형식 모두 요청에 헤더가 없음). 회차 1의 K2/K3도 같은 이유로 "헤더 없음"이었을 것으로 봄 [INFERENCE]. 빈 값이나 공백만 있는 값을 실제로 보내면 `@NotBlank` 위반으로 400이 나고, 상태 코드는 같음 |
| ProblemDetail `type` | #41 "`type/title/status/detail/instance` 형식" | 모든 에러 본문에 `type` 키가 없음 | 회귀 아님. 리팩터링이 건드리지 않은 Boot 프레임워크 400 경로(`Validation failure`, `Failed to read request`)에도 `type`이 없음. 회차 1과 회차 2의 Boot 버전(4.1.2)이 같으므로, 회차 1 #41의 `type` 표기는 형식을 일반적으로 쓴 것으로 보임 [INFERENCE: 회차 1 원시 응답이 남아 있지 않아 대조할 수 없음] |
| 서버 로그 | 1만 건 측정 중 로그 0줄 | 1만 건 측정 3회 중에도 0줄. 같은 키 20회 동시에서는 1062 WARN(`HHH000247`, `Duplicate entry … uk_items_user_idempotency`)이 충돌 1건당 2줄 | 회귀 아님(회차 1의 "0줄"은 1만 건 측정 구간 기준) |

## 완료 기준 측정 (회차 2)

**전제 (D L130)**
- QA 시작 시 `DELETE FROM items`를 실행했습니다(developer smoke 3행 포함, 아래 "테스트 데이터 정리" 참고).
- 각 1만 건 실행 직전에도 `DELETE FROM items`를 실행하고, `COUNT(*)=0`인 것을 확인했습니다.
- 부하용 유저 1,000명은 회차 1과 같은 SQL로 넣었습니다(id 1025~2024, `qa-load-0001`~`qa-load-1000`).
- 실행 명령은 `java /tmp/Qa2Load.java burst 1025 1000 10 /tmp/qa2-burst-N.txt`입니다.
- 키는 `qa-load-<userId>-<i>-<UUID>`로 모두 다르고, 본문은 회차 1과 같습니다.
- 동시 소켓 수: 1만 개 요청을 latch 하나로 동시에 출발시켰습니다. 클라이언트 in-flight 최대와 서버 쪽 ESTABLISHED 최대는 표에 적었습니다.
- 클라이언트 timeout은 요청 120초, 연결 60초입니다.
- TIME_WAIT 교훈을 반영해 실행 사이 간격을 충분히 뒀습니다(1→2: 약 87초, 2→3: 약 68초). 매 실행 직전 TIME_WAIT 수도 확인했습니다.

| 실행 | 시각(KST) | 직전 TIME_WAIT | 요청 | 동시 in-flight 최대 | 서버 ESTABLISHED 최대 | 총 소요(출발→마지막 응답) | 201 | 200 | 4xx | 5xx | 연결 실패 | p50 / p95 / p99 / max (ms) | DB 증가 | 201 수 = DB 증가 | id 집합 불일치 | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 16:34:01 | 61 | 10,000 | 10,000 | 10,000 | 3,186ms | 10,000 | 0 | 0 | 0 | 0 | 1,701 / 2,898 / 3,100 / 3,177 | 10,000 | 일치 | 0 | 합격 |
| 2 | 16:35:32 | 7 | 10,000 | 9,872 | 9,626 | 3,114ms | 10,000 | 0 | 0 | 0 | 0 | 1,635 / 2,808 / 3,042 / 3,089 | 10,000 | 일치 | 0 | 합격 |
| 3 | 16:36:43 | 2 | 10,000 | 9,708 | 9,394 | 3,089ms | 10,000 | 0 | 0 | 0 | 0 | 1,527 / 2,701 / 2,838 / 3,044 | 10,000 | 일치 | 0 | 합격 |

- 매 실행 뒤 DB를 확인했습니다. 3회 모두 `COUNT(*)=10000`, `COUNT(DISTINCT user_id)=1000`, `COUNT(DISTINCT user_id, idempotency_key)=10000`, `SUM(status='ON_SALE')=10000`이었습니다. 1회차는 유저별 건수 분포가 "10건 × 1,000명" 하나뿐인 것도 확인했습니다.
- `created_at` 범위(UTC)는 다음과 같습니다.
  - 1회차: 07:34:02.098 ~ 07:34:05.128
  - 2회차: 07:35:32.584 ~ 07:35:35.587
  - 3회차: 07:36:43.638 ~ 07:36:46.641
- id 대조: 201 응답 본문의 id 목록과 DB `SELECT id FROM items`를 각각 정렬하고 `comm -3`으로 비교했습니다. 3회 모두 불일치가 0줄이었습니다.
- 서버 로그: 1만 건 측정 세 구간(16:34:01~05, 16:35:32~35, 16:36:43~46)에 찍힌 줄은 0줄입니다.
- 판정: 유효 3회 모두 201 = 10,000(기준 9,500 이상)이고 DB 증가 = 201 수입니다. 따라서 완료 기준 **통과**입니다. 무효 실행은 없었습니다.

**같은 (유저, 키) 20회 동시** (D L131)
- 실행 명령은 `java /tmp/Qa2Load.java same 1026 20 …`이고, 유저는 1026, 키는 실행마다 새 `qa-same-<UUID>`입니다.

| 실행 | 상태 분포 | 2xx 본문 id 종류 | DB 증가 | 소요 |
|---|---|---|---|---|
| 1 | 201 ×1, 200 ×19 | 1 (id 47183) | 1 (24→25) | 78ms |
| 2 | 201 ×1, 200 ×19 | 1 (id 47202) | 1 (25→26) | 64ms |
| 3 | 201 ×1, 200 ×19 | 1 (id 47222) | 1 (26→27) | 72ms |

## 결함 (회차 2)

- **새 결함 없음.**
- **D-1: 해결됨.** 회차 1의 재현 명령을 그대로 실행했고(키 `qa-emoji-title-100`, `qa-emoji-content-5000`), 둘 다 `201`이었습니다. 위 D-1 절의 상태 표기와 #17, #21을 참고하세요.

## 테스트 커버리지 공백 (회차 2)

`./gradlew clean test` 결과 (2026-10-29 16:31:08 KST 종료): BUILD SUCCESSFUL, **92건** 중 실패 0, 오류 0, 건너뜀 0(`build/test-results/test/*.xml` 집계).

| 테스트 클래스 | 건수 | 회차 1 |
|---|---|---|
| item.presentation.ItemControllerWebMvcTest | 41 | 37 |
| item.PostApiIntegrationTest | 21 | 18 |
| item.domain.TitleTest | 11 | (신규) |
| item.PostConcurrencyIntegrationTest | 6 | 6 |
| item.application.ItemServiceTest | 4 | 4 |
| item.domain.ContentTest | 4 | (신규) |
| item.domain.PriceTest | 2 | (신규) |
| item.ItemsTableConstraintTest | 2 | 2 |
| item.domain.ItemTest | 1 | 6 (제목·가격 규칙 테스트가 VO 테스트로 옮겨짐) |

회차 1에서 공백이었던 #17, #21(서로게이트 쌍 경계)은 이제 테스트가 덮습니다.
- 단위: TitleTest, ContentTest의 이모지 100/101자, 5000/5001자
- WebMvc: 이모지 제목 101자, 내용 5001자 400과 이모지 100자, 5000자 201
- 통합: 이모지 제목 100자 DB `CHAR_LENGTH=100`, 내용 이모지 5000자

#69(내용 생략 후 GET content null)도 통합 테스트가 덮습니다.

아직 자동 테스트가 덮지 못한 항목입니다(괄호는 이번 회차의 API 확인 결과).

| 체크리스트 # | 공백 |
|---|---|
| 58, 59 | 1만 건(1,000 × 10) 자동 테스트 없음 (API로 측정, 통과) |
| 34, 35 | 실제 잠금 대기·풀 고갈 503 통합 테스트 없음, WebMvc mock만 있음 (34는 API로 통과, 35는 회차 2에서 미검증) |
| 36 | 실제 deadlock 미재현 (미검증) |
| 60~62, 44, 46 | 패키지 배치, import 규칙, 객체 연관 금지, 인터페이스 규칙의 자동 검사(ArchUnit 등) 없음. 이번에도 검색으로 직접 확인함 (정적, 통과) |
| 70 | 규칙을 어긴 저장 행을 조회하면 500이 되는 동작을 다루는 테스트 없음. VO 생성자 단위 테스트만 있음 (API로 확인, 통과) |
| 3 | 요청에 `status`를 넣어도 무시되는지 API 수준 테스트 없음 (API로 확인, 통과) |
| 19 | 내용 명시적 `null` API 테스트 없음 (API로 확인, 통과) |
| 22 | negotiable 명시적 `null` 테스트 없음 (API로 확인, 통과) |
| 27 | 빈 값이나 공백만 있는 키를 실제로 보내는 경로는 MockMvc로만 확인 (API로 확인, 통과) |
| 29 | 비ASCII 키는 MockMvc로만 확인 (API로 확인, 통과) |
| 42 | UTC 저장, KST 응답 테스트 없음 (API로 확인, 통과) |
| 48~52 | 개발 DB DDL과 `schema.sql`의 일치는 테스트 범위 밖 (DB 조회로 확인, 통과) |
| 57 | 설정값을 단언하는 테스트 없음 |

## 개선 제안 (기준 밖, 회차 2)

판정에 쓰지 않은 관찰입니다. 회차 1 개선 제안 1~8은 리팩터링으로 바뀐 내용이 없어 그대로 유효합니다. 다만 6은 위치가 바뀌었습니다(`ItemReader.java:25`의 `orElseThrow()`, 회차 2에서도 줄 번호는 같음).

1. **ProblemDetail의 `type` 필드 방침**: 모든 에러 응답에 `type`이 없습니다(#41). RFC 9457상 문제는 없습니다. 클라이언트가 `type`으로 오류를 분기해야 한다면 설계서에 오류 유형 URI를 정해 두는 것이 좋습니다.
2. **500 응답은 ProblemDetail이 아님**: V1(#70)의 500 응답은 `Content-Type: application/json`이고, 본문은 Boot 기본 오류 형식(`timestamp/status/error/path`)입니다.
   - 설계는 이 경로를 "매핑하지 않음, 도달하면 버그 → 500"(D L71-72)으로 정했고, 상태 코드는 설계와 일치합니다.
   - 그런데 D L54 "에러 응답은 ProblemDetail 형식"이 500에도 적용되는지는 설계서에 적혀 있지 않습니다. 그래서 결함으로 판정하지 않았습니다.
   - 500도 problem+json으로 통일할지 설계에서 정하는 것이 좋습니다. 같은 경로를 타는 다른 500(FK 1452, 재조회 `NoSuchElementException`)도 같은 형식일 것으로 봅니다 [INFERENCE].
3. **중복 충돌마다 WARN 로그 2줄**: 같은 키 재요청은 정상 흐름인데도 Hibernate가 `HHH000247 ErrorCode: 1062`와 `Duplicate entry …`를 WARN으로 남깁니다. 재시도가 많아지면 로그 잡음이 됩니다. 필요하면 `org.hibernate.orm.jdbc.error` 로그 레벨을 조정할 수 있습니다.
4. **AUTO_INCREMENT 번호 건너뜀**: 같은 키 충돌 시 INSERT가 실패해도 id가 하나 소비됩니다(N6 뒤 47158 결번). 기능 영향은 없고 정보로만 적습니다.

## 테스트 데이터 정리 (회차 2, 개발 DB `karrot_with_design`)

- **QA 시작 시 삭제한 데이터**: developer smoke items 3행입니다. id 47153, 47155, 47156이고, 키는 `ddd-smoke-1793258571-a`, `-e`, `-g`입니다. 설계서 전제(측정 전 items 비우기)에 따라 삭제했고 복원하지 않았습니다.
- **QA 중 넣은 데이터**:
  - 부하용 users 1,000명(id 1025~2024, `qa-load-*`)
  - items: 엣지 케이스, 같은 키 동시, 잠금, 1만 건 실행으로 생성했습니다. 마지막 1만 건 실행의 최소 id는 67242입니다.
  - V1 확인용으로 SQL로 직접 넣은 공백 제목 행(id 77242)이 있습니다. 확인 직후 삭제했습니다.
- **QA 종료 시 삭제한 데이터**: items 전부, users `nickname LIKE 'qa-load-%'` 1,000명.
- **남은 상태 (16:37:37 확인)**:
  - `items` 0행
  - `users` 1행(id=1, `design-check-user`, 그대로 둠)
  - `item_categories` 3행
  - `items`의 AUTO_INCREMENT는 되돌리지 않았습니다.
- **테스트 DB `karrot_with_design_test`**: `./gradlew clean test`가 사용했고, 매 실행 때 재생성되므로 손대지 않았습니다.
- **임시 파일**: 아래 파일을 모두 삭제했습니다. 삭제 후 `/tmp`에 `qa`가 들어간 이름이 없는 것을 확인했습니다.
  - `/tmp/qa2-curl.sh`, `/tmp/Qa2Load.java`, `/tmp/qa2-lock.sh`
  - `/tmp/qa2-out/`, `/tmp/qa2-*.txt`, `/tmp/qa2-curl-result.tsv`, `/tmp/qa2-lock-*`
- **앱**: bootRun 두 번을 모두 종료했습니다. 종료 후 PID 73793, 73764, 76931, 76902가 없고 8081 LISTEN도 없는 것을 확인했습니다.
- **소스**: 수정하지 않았습니다. 이 회차에서 쓴 파일은 이 보고서뿐입니다.
