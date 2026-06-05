import argparse
import json
import re
import time
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path


HTML_TAG = re.compile(r"<[^>]+>")
WHITESPACE = re.compile(r"\s+")
REFUSAL_PATTERNS = [
    "无法回答",
    "不能回答",
    "无法提供",
    "无法给出",
    "不属于法律问题",
    "知识库未覆盖",
    "没有检索到",
    "未检索到",
]


def clean_text(text):
    text = HTML_TAG.sub("", text or "")
    text = text.replace("回答:", "").replace("法律依据:", "")
    return WHITESPACE.sub("", text)


def tokenize(text):
    return [ch for ch in clean_text(text) if not ch.isspace()]


def ngrams(tokens, n):
    return [tuple(tokens[i:i + n]) for i in range(len(tokens) - n + 1)]


def rouge_n(prediction, reference, n):
    pred_tokens = tokenize(prediction)
    ref_tokens = tokenize(reference)
    pred_ngrams = Counter(ngrams(pred_tokens, n))
    ref_ngrams = Counter(ngrams(ref_tokens, n))

    if not pred_ngrams or not ref_ngrams:
        return 0.0, 0.0, 0.0

    overlap = sum((pred_ngrams & ref_ngrams).values())
    precision = overlap / sum(pred_ngrams.values())
    recall = overlap / sum(ref_ngrams.values())
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return precision, recall, f1


def lcs_length(a, b):
    previous = [0] * (len(b) + 1)
    for x in a:
        current = [0]
        for j, y in enumerate(b, 1):
            if x == y:
                current.append(previous[j - 1] + 1)
            else:
                current.append(max(previous[j], current[-1]))
        previous = current
    return previous[-1]


def rouge_l(prediction, reference):
    pred_tokens = tokenize(prediction)
    ref_tokens = tokenize(reference)

    if not pred_tokens or not ref_tokens:
        return 0.0, 0.0, 0.0

    lcs = lcs_length(pred_tokens, ref_tokens)
    precision = lcs / len(pred_tokens)
    recall = lcs / len(ref_tokens)
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return precision, recall, f1


def rouge_scores(prediction, reference):
    r1 = rouge_n(prediction, reference, 1)
    r2 = rouge_n(prediction, reference, 2)
    rl = rouge_l(prediction, reference)
    return {
        "rouge_1": {"precision": r1[0], "recall": r1[1], "f1": r1[2]},
        "rouge_2": {"precision": r2[0], "recall": r2[1], "f1": r2[2]},
        "rouge_l": {"precision": rl[0], "recall": rl[1], "f1": rl[2]},
    }


def field(item, *names):
    for name in names:
        value = item.get(name)
        if value:
            return value
    return ""


def is_refusal(text):
    return any(pattern in (text or "") for pattern in REFUSAL_PATTERNS)


def chat(base_url, question, memory_id, timeout):
    params = urllib.parse.urlencode({"message": question, "memoryId": memory_id})
    url = f"{base_url.rstrip('/')}/chat?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "consultant3-qa-rouge-batch"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


def average(items, metric):
    values = [item["rouge"][metric]["f1"] for item in items if item.get("rouge")]
    return sum(values) / len(values) if values else 0.0


def case_path(output_dir, index):
    return output_dir / f"case-{index:04d}.json"


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Run local /chat for legal QA pairs and compute per-case ROUGE.")
    parser.add_argument("input", help="JSON array with question/answer or query/reference fields.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output-dir", default="target/rouge-results")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--skip-existing", action="store_true")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    if args.limit > 0:
        data = data[:args.limit]

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for index, item in enumerate(data, 1):
        path = case_path(output_dir, index)
        if args.skip_existing and path.exists():
            existing = json.loads(path.read_text(encoding="utf-8"))
            results.append(existing)
            print(f"SKIP {index}/{len(data)} {path}")
            continue

        question = field(item, "question", "query")
        reference = field(item, "reference", "reference_answer", "expected_answer", "answer")
        case = {
            "id": item.get("id", index),
            "question": question,
            "reference": reference,
            "status": "error",
            "prediction": "",
            "rouge": None,
            "elapsed_ms": 0,
        }

        start = time.time()
        try:
            prediction = chat(args.base_url, question, f"qa-rouge-{index}", args.timeout)
            case["prediction"] = prediction
            case["elapsed_ms"] = round((time.time() - start) * 1000)
            if is_refusal(prediction):
                case["status"] = "refused"
            else:
                case["status"] = "answered"
                case["rouge"] = rouge_scores(prediction, reference)
        except Exception as exc:
            case["elapsed_ms"] = round((time.time() - start) * 1000)
            case["error"] = str(exc)

        write_json(path, case)
        results.append(case)
        print(f"{case['status'].upper()} {index}/{len(data)} {path}")

    answered = [item for item in results if item["status"] == "answered"]
    refused = [item for item in results if item["status"] == "refused"]
    errors = [item for item in results if item["status"] == "error"]
    summary = {
        "total": len(results),
        "answered": len(answered),
        "refused": len(refused),
        "errors": len(errors),
        "average_elapsed_ms": round(sum(item.get("elapsed_ms", 0) for item in results) / len(results), 2) if results else 0,
        "rouge_average": {
            "rouge_1_f1": average(answered, "rouge_1"),
            "rouge_2_f1": average(answered, "rouge_2"),
            "rouge_l_f1": average(answered, "rouge_l"),
        },
    }
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
