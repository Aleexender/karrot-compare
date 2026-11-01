"""General HTTP/API behaviour checks, independent of the feature spec.

A case fails when the app (a) answers 5xx or drops the connection, (b) accepts input but
stores something different from what was sent, or (c) breaks a basic HTTP convention.
Run from measurement/: python3 general.py  (both apps up; with-design users 100001..100100 seeded)
"""
import json, subprocess, threading, urllib.request, urllib.error, uuid
from functional import WITH, NO, MYSQL, is2xx, is4xx

def send(app, method="POST", path=None, body=None, content_type="application/json", user=1):
    headers = {"Idempotency-Key": uuid.uuid4().hex}
    if content_type:
        headers["Content-Type"] = content_type
    if app.name == "with-design":
        headers["X-User-Id"] = str(user)
    req = urllib.request.Request(app.base + (path or app.path), data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()
    except Exception as e:  # connection reset etc. counts as an outage
        return 0, {}, repr(e).encode()

def payload(app, user=1, **fields):
    body = {"title": "맥북 에어 M3", "content": "거의 새것", "price": 1200000, "negotiable": False, "categoryId": 1}
    body.update(fields)
    if app.name == "no-design":
        body["sellerId"] = user
        body["location"] = "서울"
    return body

def post_json(app, user=1, **fields):
    return send(app, body=json.dumps(payload(app, user, **fields)).encode(), user=user)

def stored_hex(app, item_id, column):
    sql = f"SELECT HEX({column}) FROM {app.table} WHERE id={int(item_id)}"
    return subprocess.run(MYSQL + [app.db, "-e", sql], capture_output=True, text=True).stdout.strip()

def round_trip(column, value, **extra):
    """2xx must store exactly `value`; 4xx is an acceptable rejection; 5xx fails."""
    def run(app):
        s, _, b = post_json(app, **{column: value}, **extra)
        if not is2xx(s):
            return is4xx(s), f"HTTP {s} ({'거부' if is4xx(s) else '서버 오류'})"
        stored = stored_hex(app, json.loads(b)["id"], column)
        expected = value.encode("utf-8", "surrogatepass").hex().upper() if isinstance(value, str) else None
        if isinstance(value, int):
            got = subprocess.run(MYSQL + [app.db, "-e", f"SELECT {column} FROM {app.table} WHERE id={json.loads(b)['id']}"],
                                 capture_output=True, text=True).stdout.strip()
            return got == str(value), f"HTTP {s}, 저장값 {got}"
        return stored == expected, f"HTTP {s}, 저장값 {'일치' if stored == expected else '불일치 (' + bytes.fromhex(stored).decode('utf-8', 'replace')[:20] + ')'}"
    return run

def expect(pred, label, **kw):
    def run(app):
        s, h, b = send(app, **kw)
        return pred(s), f"HTTP {s}"
    return run

def raw(body, **kw):
    return expect(is4xx, "", body=body, **kw)

def run_unknown_fields(app):
    body = payload(app); body["unknownField"] = "x"; body["nested"] = {"a": [1, 2]}
    s, _, _ = send(app, body=json.dumps(body).encode())
    return is2xx(s), f"HTTP {s}"

def run_deep_nesting(app):
    body = json.dumps(payload(app))[:-1] + ',"deep":' + "[" * 5000 + "]" * 5000 + "}"
    s, _, _ = send(app, body=body.encode())
    return is4xx(s), f"HTTP {s}"

def run_injection(app):
    ok, obs = round_trip("title", "'); DROP TABLE items; DROP TABLE posts; --")(app)
    alive = WITH.count() >= 0 and NO.count() >= 0
    return ok and alive, obs + ", 테이블 유지"

def run_location(app):
    s, h, _ = post_json(app)
    loc = h.get("Location") or h.get("location")
    return s == 201 and bool(loc), f"HTTP {s}, Location={loc}"

def run_json_content_type(app):
    s, h, _ = post_json(app)
    ct = h.get("Content-Type", "")
    return "json" in ct, f"Content-Type={ct}"

def run_error_json(app):
    s, h, _ = send(app, body=b'{"title":')
    ct = h.get("Content-Type", "")
    return is4xx(s) and "json" in ct, f"HTTP {s}, Content-Type={ct}"

def run_parallel(n, distinct_users):
    def run(app):
        before, codes, lock = app.count(), [], threading.Lock()
        barrier = threading.Barrier(n)
        def go(i):
            barrier.wait()
            s, _, _ = post_json(app, user=100001 + i if distinct_users else 1, title=f"병렬 {i}")
            with lock: codes.append(s)
        ts = [threading.Thread(target=go, args=(i,)) for i in range(n)]
        [t.start() for t in ts]; [t.join() for t in ts]
        d = app.count() - before
        ok2 = sum(map(is2xx, codes))
        return ok2 == n and d == n, f"2xx {ok2}/{n}, DB +{d}"
    return run

def run_after_garbage(app):
    for _ in range(500):
        send(app, body=b"{not json")
    s, _, _ = post_json(app)
    return is2xx(s), f"잘못된 요청 500건 후 정상 요청 HTTP {s}"

def run_price_overflow(app):
    body = json.dumps(payload(app, price=0)).replace('"price": 0', '"price": 9223372036854775808')
    s, _, _ = send(app, body=body.encode())
    return is4xx(s), f"HTTP {s}"

CASES = [
    # HTTP / 형식
    ("G1", "형식", "Content-Type: text/plain", "4xx (415)", raw(json.dumps({"title": "x"}).encode(), content_type="text/plain")),
    ("G2", "형식", "본문 없음", "4xx", raw(b"")),
    ("G3", "형식", "본문이 JSON 배열 []", "4xx", raw(b"[]")),
    ("G4", "형식", "본문이 JSON null", "4xx", raw(b"null")),
    ("G5", "형식", "빈 객체 {}", "4xx", raw(b"{}")),
    ("G6", "형식", "잘못된 UTF-8 바이트", "4xx", raw(b'{"title":"\xff\xfe","price":1000}')),
    ("G7", "형식", "5,000단계 중첩 JSON", "4xx (5xx 아님)", run_deep_nesting),
    ("G8", "형식", "모르는 필드 추가", "2xx (무시)", run_unknown_fields),
    ("G9", "메서드", "GET 컬렉션 경로", "405", expect(lambda s: s == 405, "", method="GET", body=None, content_type=None)),
    ("G10", "메서드", "PUT 컬렉션 경로", "405", expect(lambda s: s == 405, "", method="PUT", body=b"{}")),
    ("G11", "메서드", "DELETE 컬렉션 경로", "405", expect(lambda s: s == 405, "", method="DELETE", body=None, content_type=None)),
    ("G12", "메서드", "없는 하위 경로에 POST", "4xx", lambda app: expect(is4xx, "", path=app.path + "/x/y", body=b"{}")(app)),
    # 데이터 보존
    ("G13", "데이터", "이모지 제목 🍎×10", "2xx, 저장값 일치", round_trip("title", "🍎" * 10)),
    ("G14", "데이터", "SQL 인젝션 형태 제목", "2xx, 그대로 저장, 테이블 유지", run_injection),
    ("G15", "데이터", "<script> 제목", "그대로 저장 또는 4xx", round_trip("title", "<script>alert(1)</script>")),
    ("G16", "데이터", "짝 없는 서로게이트 \\ud800", "4xx 또는 그대로 저장", round_trip("title", "가\ud800나")),
    ("G17", "데이터", "NUL 문자 포함 제목", "4xx 또는 그대로 저장", round_trip("title", "a\u0000b")),
    ("G18", "데이터", "내용 70,000자 (ASCII)", "4xx 또는 그대로 저장 (5xx 아님)", round_trip("content", "a" * 70000)),
    ("G19", "데이터", "내용 이모지 20,000자 (80KB)", "4xx 또는 그대로 저장 (5xx 아님)", round_trip("content", "😀" * 20000)),
    ("G20", "데이터", "가격 2^63 (Long 초과)", "4xx", run_price_overflow),
    ("G21", "데이터", "가격 30억", "4xx 또는 그대로 저장", round_trip("price", 3_000_000_000)),
    # 응답 규약
    ("G22", "응답", "201 응답에 Location 헤더", "있음 (RFC 9110)", run_location),
    ("G23", "응답", "성공 응답 Content-Type", "JSON", run_json_content_type),
    ("G24", "응답", "오류 응답 Content-Type", "4xx + JSON", run_error_json),
    # 동시성·안정성
    ("G25", "동시성", "같은 유저 다른 글 50개 동시", "모두 2xx, DB +50", run_parallel(50, False)),
    ("G26", "동시성", "다른 유저 100명 동시", "모두 2xx, DB +100", run_parallel(100, True)),
    ("G27", "안정성", "잘못된 요청 500건 직후 정상 요청", "2xx", run_after_garbage),
]

if __name__ == "__main__":
    results = {}
    for app in (WITH, NO):
        for cid, group, desc, expectation, run in CASES:
            try:
                passed, observed = run(app)
            except Exception as e:
                passed, observed = False, f"harness error: {e!r}"
            results.setdefault(cid, {"group": group, "desc": desc, "expect": expectation})[app.name] = {
                "pass": passed, "observed": observed}
            print(f"{app.name:12} {cid:4} {'PASS' if passed else 'FAIL'}  {desc} — {observed}", flush=True)
    json.dump(results, open("general.json", "w"), ensure_ascii=False, indent=1)
