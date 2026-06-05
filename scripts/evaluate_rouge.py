import argparse
import json
import re
import sys
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
    "请咨询专业律师",
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


def average(values):
    return sum(values) / len(values) if values else 0.0


def field(item, *names):
    for name in names:
        value = item.get(name)
        if value:
            return value
    return ""


def is_refusal(text):
    return any(pattern in (text or "") for pattern in REFUSAL_PATTERNS)


def chat(base_url, question, memory_id):
    params = urllib.parse.urlencode({"message": question, "memoryId": memory_id})
    url = f"{base_url.rstrip('/')}/chat?{params}"
    req = urllib.request.Request(url, headers={"User-Agent": "consultant3-rouge-eval"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        return resp.read().decode("utf-8", errors="replace")


def score_item(item):
    prediction = field(item, "prediction", "generated", "generated_answer")
    reference = field(item, "reference", "reference_answer", "expected_answer", "answer")

    r1 = rouge_n(prediction, reference, 1)
    r2 = rouge_n(prediction, reference, 2)
    rl = rouge_l(prediction, reference)

    return {
        "id": item.get("id"),
        "question": field(item, "query", "question"),
        "rouge_1": {"precision": r1[0], "recall": r1[1], "f1": r1[2]},
        "rouge_2": {"precision": r2[0], "recall": r2[1], "f1": r2[2]},
        "rouge_l": {"precision": rl[0], "recall": rl[1], "f1": rl[2]},
    }


def print_summary(scores):
    print(f"cases: {len(scores)}")
    for key, label in [("rouge_1", "ROUGE-1"), ("rouge_2", "ROUGE-2"), ("rouge_l", "ROUGE-L")]:
        ps = [s[key]["precision"] for s in scores]
        rs = [s[key]["recall"] for s in scores]
        fs = [s[key]["f1"] for s in scores]
        print(f"{label:<8} P: {average(ps):.4f}  R: {average(rs):.4f}  F1: {average(fs):.4f}")


def main():
    parser = argparse.ArgumentParser(description="Evaluate Chinese legal QA answers with ROUGE-1/2/L.")
    parser.add_argument("input", nargs="?", default="src/test/java/dataset/generation-eval-sample.json")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--generate", action="store_true", help="Call /chat with question/query to fill prediction fields.")
    parser.add_argument("--limit", type=int, default=0, help="Evaluate only the first N records.")
    parser.add_argument("--output", help="Write per-case scores and filled predictions to this JSON file.")
    parser.add_argument("--skip-refusals", action="store_true", help="Exclude refusal-style predictions from ROUGE scoring.")
    args = parser.parse_args()

    path = Path(args.input)
    data = json.loads(path.read_text(encoding="utf-8"))
    if args.limit > 0:
        data = data[:args.limit]

    filled = []
    scores = []
    skipped = []
    for index, item in enumerate(data, 1):
        current = dict(item)
        if args.generate and not field(current, "prediction", "generated", "generated_answer"):
            question = field(current, "query", "question")
            current["prediction"] = chat(args.base_url, question, f"rouge-eval-{index}")
            print(f"generated {index}/{len(data)}", file=sys.stderr)

        filled.append(current)
        prediction = field(current, "prediction", "generated", "generated_answer")
        if args.skip_refusals and is_refusal(prediction):
            skipped.append(current)
            continue
        scores.append(score_item(current))

    print_summary(scores)
    if skipped:
        print(f"skipped refusals: {len(skipped)}")

    if args.output:
        output = {
            "summary": {
                "cases": len(scores),
                "skipped_refusals": len(skipped),
                "rouge_1_f1": average([s["rouge_1"]["f1"] for s in scores]),
                "rouge_2_f1": average([s["rouge_2"]["f1"] for s in scores]),
                "rouge_l_f1": average([s["rouge_l"]["f1"] for s in scores]),
            },
            "items": [dict(item, scores=score) for item, score in zip([x for x in filled if not (args.skip_refusals and is_refusal(field(x, "prediction", "generated", "generated_answer")))], scores)],
            "skipped": skipped,
        }
        Path(args.output).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
