import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass


@dataclass
class Result:
    name: str
    passed: bool
    detail: str = ""


def request(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "consultant3-api-smoke-test"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return resp.status, resp.headers.get("Content-Type", ""), body
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return exc.code, exc.headers.get("Content-Type", ""), body


def test_home(base_url):
    status, content_type, body = request(f"{base_url}/", timeout=10)
    ok = status == 200 and ("text/html" in content_type or "<!" in body or "<html" in body.lower())
    return Result("首页可访问", ok, f"status={status}, content-type={content_type}")


def test_sources(base_url):
    query = urllib.parse.quote("老板不给工资怎么办")
    status, content_type, body = request(f"{base_url}/chat/sources?message={query}", timeout=90)
    if status != 200:
        return Result("法条溯源接口", False, f"status={status}")
    try:
        data = json.loads(body)
    except json.JSONDecodeError as exc:
        return Result("法条溯源接口", False, f"invalid json: {exc}")
    if not isinstance(data, list):
        return Result("法条溯源接口", False, "response is not a list")
    if data:
        first = data[0]
        required = {"law_name", "article_number", "text_preview"}
        missing = required - set(first.keys())
        if missing:
            return Result("法条溯源接口", False, f"missing fields: {sorted(missing)}")
    return Result("法条溯源接口", True, f"status={status}, sources={len(data)}")


def test_chat(base_url):
    params = urllib.parse.urlencode({"message": "老板不给工资怎么办", "memoryId": "api-smoke-test"})
    status, _, body = request(f"{base_url}/chat?{params}", timeout=180)
    ok = status == 200 and len(body.strip()) > 0
    return Result("游客问答接口", ok, f"status={status}, chars={len(body.strip())}")


def test_conversation_requires_auth(base_url):
    status, _, body = request(f"{base_url}/api/conversation/list", timeout=10)
    ok = status == 401
    return Result("未登录会话接口返回401", ok, f"status={status}, body={body[:80]}")


def main():
    base_url = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
    tests = [test_home, test_sources, test_chat, test_conversation_requires_auth]

    results = []
    for test in tests:
        try:
            results.append(test(base_url))
        except Exception as exc:
            results.append(Result(test.__name__, False, str(exc)))

    passed = 0
    for result in results:
        prefix = "PASS" if result.passed else "FAIL"
        if result.passed:
            passed += 1
        print(f"{prefix} {result.name} - {result.detail}")

    failed = len(results) - passed
    print(f"\n{passed} passed, {failed} failed")
    raise SystemExit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
