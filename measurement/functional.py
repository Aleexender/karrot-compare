"""Requirement-based functional checks run identically against both apps.

Each app gets an adapter that turns one logical request (user, idempotency key, fields)
into that app's real HTTP contract. Verdicts use HTTP status + DB row delta.
"""
import json, subprocess, sys, threading, urllib.request, urllib.error, uuid

MYSQL = ["docker", "exec", "karrot-mysql", "mysql", "-ukarrot", "-pkarrot", "-N", "-B"]
OMIT = object()


class App:
    def __init__(self, name, base, path, db, table):
        self.name, self.base, self.path, self.db, self.table = name, base, path, db, table

    def count(self):
        out = subprocess.run(MYSQL + [self.db, "-e", f"SELECT COUNT(*) FROM {self.table}"],
                             capture_output=True, text=True).stdout.strip()
        return int(out)

    def post(self, user=1, key=None, path=None, raw=None, **fields):
        key = key or uuid.uuid4().hex
        body = {"title": "맥북 에어 M3", "content": "거의 새것", "price": 1200000,
                "negotiable": False, "categoryId": 1}
        body.update(fields)
        body = {k: v for k, v in body.items() if v is not OMIT}
        headers = {"Content-Type": "application/json", "Idempotency-Key": key}
        if self.name == "with-design":
            headers["X-User-Id"] = str(user)
        else:
            body["sellerId"] = user
            body["location"] = "서울"  # no-design requires it; not part of the spec
        data = raw.encode() if raw is not None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + (path or self.path), data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, None


WITH = App("with-design", "http://localhost:8081", "/posts", "karrot_with_design", "items")
NO = App("no-design", "http://localhost:8082", "/api/posts", "karrot_no_design", "posts")


def is2xx(s): return 200 <= s < 300
def is4xx(s): return 400 <= s < 500


def concurrent_same_key(app, n):
    key, barrier, results = uuid.uuid4().hex, threading.Barrier(n), []
    def go():
        barrier.wait()
        results.append(app.post(key=key)[0])
    ts = [threading.Thread(target=go) for _ in range(n)]
    [t.start() for t in ts]; [t.join() for t in ts]
    return results


# (id, group, requirement source, description, expectation, runner(app) -> (pass, observed))
def case_status_reject(fields=None, raw=None, user=1):
    def run(app):
        before = app.count()
        s, _ = app.post(user=user, raw=raw, **(fields or {}))
        d = app.count() - before
        return is4xx(s) and d == 0, f"HTTP {s}, DB +{d}"
    return run


def case_accept(fields):
    def run(app):
        before = app.count()
        s, _ = app.post(**fields)
        d = app.count() - before
        return is2xx(s) and d == 1, f"HTTP {s}, DB +{d}"
    return run


def run_create(app):
    before = app.count(); s, _ = app.post(); d = app.count() - before
    return is2xx(s) and d == 1, f"HTTP {s}, DB +{d}"

def run_multi(app):
    before = app.count(); s1, _ = app.post(title="글 1"); s2, _ = app.post(title="글 2"); d = app.count() - before
    return is2xx(s1) and is2xx(s2) and d == 2, f"HTTP {s1}/{s2}, DB +{d}"

def run_status(app):
    s, b = app.post(); v = (b or {}).get("status")
    return v in ("ON_SALE", "SELLING"), f"status={v}"

def run_negotiable(app):
    s, b = app.post(negotiable=True); v = (b or {}).get("negotiable")
    return v is True, f"HTTP {s}, negotiable={v}"

def run_category(app):
    s, b = app.post(categoryId=2); v = (b or {}).get("categoryId")
    return v == 2, f"HTTP {s}, categoryId={v}"

def run_path(app):
    s, _ = app.post(path="/posts")
    return is2xx(s), f"POST /posts → HTTP {s}"

def run_double_concurrent(app, trials=10):
    ok, obs = 0, []
    for _ in range(trials):
        before = app.count(); codes = concurrent_same_key(app, 2); d = app.count() - before
        ok += d == 1; obs.append(d)
    return ok == trials, f"{trials}회 중 1건 저장 {ok}회 (회차별 DB 증가 {obs})"

def run_double_sequential(app):
    key = uuid.uuid4().hex; before = app.count()
    s1, _ = app.post(key=key); s2, _ = app.post(key=key); d = app.count() - before
    return d == 1, f"HTTP {s1}/{s2}, DB +{d}"

def run_triple_concurrent(app, trials=10):
    ok, obs = 0, []
    for _ in range(trials):
        before = app.count(); concurrent_same_key(app, 3); d = app.count() - before
        ok += d == 1; obs.append(d)
    return ok == trials, f"{trials}회 중 1건 저장 {ok}회 (회차별 DB 증가 {obs})"

def run_same_key_other_users(app):
    key = uuid.uuid4().hex; before = app.count()
    s1, _ = app.post(user=1, key=key); s2, _ = app.post(user=2, key=key); d = app.count() - before
    return d == 2, f"HTTP {s1}/{s2}, DB +{d}"

def run_price_fraction(app):
    before = app.count(); s, b = app.post(price=12.5); d = app.count() - before
    stored = (b or {}).get("price")
    return is4xx(s) and d == 0, f"HTTP {s}, DB +{d}" + (f", 저장된 가격={stored}" if stored is not None else "")


CASES = [
    ("A1", "명시", "01 L4-5", "정상 등록", "2xx, DB +1", run_create),
    ("A2", "명시", "01 L4", "한 유저가 게시글 2개 등록", "둘 다 2xx, DB +2", run_multi),
    ("A3", "명시", "01 L6", "등록 시 판매 상태 = 판매중", "status ∈ {ON_SALE, SELLING}", run_status),
    ("A4", "명시", "01 L6", "네고가능 true 저장", "응답 negotiable = true", run_negotiable),
    ("A5", "명시", "01 L7", "카테고리(옷/패션=2) 지정", "응답 categoryId = 2", run_category),
    ("A6", "명시", "01 L5", "API 경로 POST /posts", "2xx", run_path),
    ("A7", "명시", "01 L14", "동시에 두 번 클릭 (같은 키, 10회 반복)", "매회 DB +1", run_double_concurrent),
    ("A8", "명시", "01 L14", "빠르게 연속 두 번 클릭 (같은 키, 순차)", "DB +1", run_double_sequential),
    ("A9", "명시", "01 L15", "가격 필드 누락", "4xx, DB +0", case_status_reject({"price": OMIT})),
    ("A10", "명시", "01 L15", "가격 null", "4xx, DB +0", case_status_reject({"price": None})),
    ("A11", "명시", "01 L16", "제목 필드 누락", "4xx, DB +0", case_status_reject({"title": OMIT})),
    ("A12", "명시", "01 L16", "제목 빈 문자열", "4xx, DB +0", case_status_reject({"title": ""})),
    ("A13", "명시", "01 L17", "내용 필드 누락", "2xx, DB +1", case_accept({"content": OMIT})),
    ("A14", "명시", "01 L17", "내용 빈 문자열", "2xx, DB +1", case_accept({"content": ""})),
    ("B1", "추론", "planner", "제목 공백만", "4xx, DB +0", case_status_reject({"title": "   "})),
    ("B2", "추론", "planner", "가격 음수", "4xx, DB +0", case_status_reject({"price": -1000})),
    ("B3", "추론", "planner", "제목 101자", "4xx (5xx 아님), DB +0", case_status_reject({"title": "가" * 101})),
    ("B4", "추론", "planner", "JSON 문법 오류", "4xx, DB +0", case_status_reject(raw='{"title": "x",')),
    ("B5", "추론", "planner", "가격 타입 오류 \"abc\"", "4xx, DB +0", case_status_reject({"price": "abc"})),
    ("B6", "추론", "planner", "가격 소수 12.5", "4xx, DB +0 (잘려서 저장되면 안 됨)", run_price_fraction),
    ("B7", "추론", "planner", "존재하지 않는 유저(999999)", "4xx, DB +0", case_status_reject(user=999999)),
    ("B8", "추론", "planner", "세 번 연타 (같은 키 동시 3회, 10회 반복)", "매회 DB +1", run_triple_concurrent),
    ("B9", "추론", "planner", "다른 유저가 같은 키 사용", "DB +2 (서로 독립)", run_same_key_other_users),
]

if __name__ == "__main__":
    results = {}
    for app in (WITH, NO):
        for cid, group, src, desc, expect, run in CASES:
            try:
                passed, observed = run(app)
            except Exception as e:  # harness errors are recorded, never silently passed
                passed, observed = False, f"harness error: {e!r}"
            results.setdefault(cid, {"group": group, "src": src, "desc": desc, "expect": expect})[app.name] = {
                "pass": passed, "observed": observed}
            print(f"{app.name:12} {cid:4} {'PASS' if passed else 'FAIL'}  {desc} — {observed}", flush=True)
    json.dump(results, open("functional.json", "w"), ensure_ascii=False, indent=1)
