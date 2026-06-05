# Dynamic Hybrid Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a testable query-type-aware dynamic hybrid retrieval strategy while preserving existing vector retrieval, keyword retrieval, weighted RRF, deduplication, max result limiting, and query rewrite behavior.

**Architecture:** Keep the behavior inside `HybridContentRetriever` so the existing `CommonConfig` wiring and `QueryRewriteRetriever` wrapper do not change. Add rule-based query classification, dynamic RRF weights, and keyword-result admission filtering before fusion. Keep `KeywordContentRetriever` unchanged for this test version.

**Tech Stack:** Java, Spring Boot project structure, LangChain4j `ContentRetriever`, JUnit 5 tests, Maven test execution.

---

## File Structure

- Modify: `src/test/java/org/example/consultant3/aiservice/config/HybridContentRetrieverTest.java`
  - Adds TDD coverage for exact-query keyword boost, colloquial-query keyword suppression, keyword admission filtering, and legal-term admission.
- Modify: `src/main/java/org/example/consultant3/aiservice/config/HybridContentRetriever.java`
  - Adds query classification, dynamic weights, keyword filtering, and weighted fusion overload.
- No changes: `src/main/java/org/example/consultant3/aiservice/config/KeywordContentRetriever.java`
  - Existing keyword extraction and ranking remain intact.
- No changes: `src/main/java/org/example/consultant3/aiservice/config/QueryRewriteRetriever.java`
  - Existing original + rewritten query expansion remains intact.

---

### Task 1: Add failing tests for dynamic hybrid retrieval

**Files:**
- Modify: `src/test/java/org/example/consultant3/aiservice/config/HybridContentRetrieverTest.java`

- [ ] **Step 1: Add tests to `HybridContentRetrieverTest`**

Insert these tests before the `// ==================== 论文数据验证 ====================` section:

```java
    // ==================== 动态混合检索 ====================

    @Test
    @DisplayName("动态权重：精确法条查询提高关键词结果排序")
    void exactArticleQueryBoostsKeywordResults() {
        List<Content> vectorResults = List.of(
                content("民法典", "第667条", "借款合同是借款人向贷款人借款，到期返还借款并支付利息的合同。"),
                content("民法典", "第668条", "借款合同应当采用书面形式，但是自然人之间借款另有约定的除外。")
        );
        List<Content> keywordResults = List.of(
                content("民法典", "第148条", "第一百四十八条 一方以欺诈手段，使对方在违背真实意思的情况下实施的民事法律行为，受欺诈方有权请求人民法院或者仲裁机构予以撤销。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("民法典第148条是什么"));

        assertEquals("第148条", result.get(0).textSegment().metadata().getString("article_number"));
    }

    @Test
    @DisplayName("动态权重：口语化查询不会让低质量关键词结果挤掉向量首位")
    void colloquialQueryKeepsVectorResultAheadOfWeakKeywordResult() {
        List<Content> vectorResults = List.of(
                content("反家庭暴力法", "第2条", "本法所称家庭暴力，是指家庭成员之间以殴打、捆绑、残害、限制人身自由以及经常性谩骂、恐吓等方式实施的身体、精神等侵害行为。")
        );
        List<Content> keywordResults = List.of(
                content("道路交通安全法", "第90条", "机动车驾驶人违反道路交通安全法律、法规关于道路通行规定的，处警告或者罚款。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("老公打我怎么办"));

        assertEquals("反家庭暴力法", result.get(0).textSegment().metadata().getString("law_name"));
        assertEquals(1, result.size(), "低质量关键词结果不应进入融合结果");
    }

    @Test
    @DisplayName("准入门槛：关键词结果不命中有效信号时不参与融合")
    void keywordResultsWithoutAdmissionSignalsAreFilteredOut() {
        List<Content> vectorResults = List.of(
                content("民法典", "第1079条", "夫妻一方要求离婚的，可以由有关组织进行调解或者直接向人民法院提起离婚诉讼。")
        );
        List<Content> keywordResults = List.of(
                content("产品质量法", "第1条", "为了加强对产品质量的监督管理，提高产品质量水平，明确产品质量责任，保护消费者的合法权益，制定本法。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("想离婚怎么办"));

        assertEquals(1, result.size());
        assertEquals("民法典", result.get(0).textSegment().metadata().getString("law_name"));
    }

    @Test
    @DisplayName("准入门槛：核心法律术语命中时关键词结果可以参与融合")
    void legalTermKeywordResultCanEnterFusion() {
        List<Content> vectorResults = List.of(
                content("民法典", "第143条", "具备相应条件的民事法律行为有效。")
        );
        List<Content> keywordResults = List.of(
                content("民法典", "第144条", "无民事行为能力人实施的民事法律行为无效。合同无效相关规则应结合民事法律行为效力规定判断。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("合同无效怎么认定"));

        List<String> articles = result.stream()
                .map(c -> c.textSegment().metadata().getString("article_number"))
                .toList();
        assertTrue(articles.contains("第144条"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./mvnw -Dtest=HybridContentRetrieverTest test
```

Expected: at least these new tests fail because the current implementation always uses fixed constructor weights and does not filter keyword results by admission signals.

---

### Task 2: Implement dynamic query classification and weighted fusion

**Files:**
- Modify: `src/main/java/org/example/consultant3/aiservice/config/HybridContentRetriever.java`

- [ ] **Step 1: Add imports/constants and query type model**

In `HybridContentRetriever.java`, keep existing imports and add these fields inside the class, after `private final double keywordWeight;`:

```java
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[零一二三四五六七八九十百千万\\d]+[条款章节]");
    private static final Pattern LAW_NAME_PATTERN = Pattern.compile("(民法典|劳动合同法|劳动法|刑法|道路交通安全法|反家庭暴力法|工伤保险条例|消费者权益保护法|产品质量法|民事诉讼法|行政诉讼法|个人所得税法|物业管理条例|医疗事故处理条例)");
    private static final Set<String> LEGAL_TERMS = Set.of(
            "合同无效", "无效合同", "工伤认定", "遗产继承", "法定继承", "劳动仲裁",
            "交通事故", "家庭暴力", "离婚诉讼", "民间借贷", "高利贷", "违约责任",
            "侵权责任", "劳动合同", "试用期", "经济补偿", "物业费", "医疗事故",
            "消费者权益", "产品质量", "行政处罚", "行政复议"
    );
    private static final double KEYWORD_SCORE_THRESHOLD = 0.4;
```

Add missing imports at the top:

```java
import java.util.regex.Pattern;
```

Add this enum and record near the bottom of the class before `RRFEntry`:

```java
    private enum QueryType {
        EXACT,
        LEGAL_TERM,
        COLLOQUIAL
    }

    private record RetrievalWeights(double vector, double keyword) {}
```

- [ ] **Step 2: Change `retrieve` to choose dynamic weights and filter keywords**

Replace the current `retrieve` method with:

```java
    @Override
    public List<Content> retrieve(Query query) {
        List<Content> vectorResults = vectorRetriever.retrieve(query);
        List<Content> keywordResults = keywordRetriever.retrieve(query);

        String queryText = query != null ? query.text() : "";
        QueryType queryType = classify(queryText);
        List<Content> admittedKeywordResults = filterKeywordResults(queryText, keywordResults);
        RetrievalWeights weights = weightsFor(queryType, admittedKeywordResults);

        return fuse(vectorResults, admittedKeywordResults, weights);
    }
```

- [ ] **Step 3: Replace fixed-weight `fuse` with weighted overload**

Replace the current `private List<Content> fuse(List<Content> listA, List<Content> listB)` method with:

```java
    private List<Content> fuse(List<Content> listA, List<Content> listB, RetrievalWeights weights) {
        Map<String, RRFEntry> entries = new LinkedHashMap<>();

        for (int i = 0; i < listA.size(); i++) {
            Content c = listA.get(i);
            String key = deduplicationKey(c);
            entries.computeIfAbsent(key, k -> new RRFEntry(c))
                    .addScore(weights.vector() / (RRF_K + i + 1));
        }

        for (int i = 0; i < listB.size(); i++) {
            Content c = listB.get(i);
            String key = deduplicationKey(c);
            entries.computeIfAbsent(key, k -> new RRFEntry(c))
                    .addScore(weights.keyword() / (RRF_K + i + 1));
        }

        return entries.values().stream()
                .sorted(Comparator.comparingDouble(RRFEntry::score).reversed())
                .limit(maxResults)
                .map(RRFEntry::content)
                .collect(Collectors.toList());
    }
```

- [ ] **Step 4: Add classification and filtering helpers**

Add these methods after `fuse` and before `deduplicationKey`:

```java
    private QueryType classify(String queryText) {
        if (ARTICLE_PATTERN.matcher(queryText).find() || LAW_NAME_PATTERN.matcher(queryText).find() || queryText.contains("《")) {
            return QueryType.EXACT;
        }
        if (LEGAL_TERMS.stream().anyMatch(queryText::contains)) {
            return QueryType.LEGAL_TERM;
        }
        return QueryType.COLLOQUIAL;
    }

    private RetrievalWeights weightsFor(QueryType queryType, List<Content> admittedKeywordResults) {
        if (admittedKeywordResults.isEmpty()) {
            return new RetrievalWeights(vectorWeight, 0.0);
        }
        return switch (queryType) {
            case EXACT -> new RetrievalWeights(1.0, 1.5);
            case LEGAL_TERM -> new RetrievalWeights(1.0, 0.8);
            case COLLOQUIAL -> new RetrievalWeights(1.0, 0.2);
        };
    }

    private List<Content> filterKeywordResults(String queryText, List<Content> keywordResults) {
        List<String> effectiveKeywords = extractEffectiveKeywords(queryText);
        return keywordResults.stream()
                .filter(content -> isAdmittedKeywordResult(queryText, content, effectiveKeywords))
                .collect(Collectors.toList());
    }

    private boolean isAdmittedKeywordResult(String queryText, Content content, List<String> effectiveKeywords) {
        TextSegment seg = content.textSegment();
        String lawName = seg.metadata().getString("law_name");
        String articleNumber = seg.metadata().getString("article_number");
        String text = seg.text()
                + (lawName != null ? " " + lawName : "")
                + (articleNumber != null ? " " + articleNumber : "");

        if (lawName != null && queryText.contains(lawName)) {
            return true;
        }
        if (articleNumber != null && queryText.contains(articleNumber.replaceAll("[^零一二三四五六七八九十百千万\\d]", ""))) {
            return true;
        }
        if (LEGAL_TERMS.stream().anyMatch(term -> queryText.contains(term) && text.contains(term))) {
            return true;
        }

        long hits = effectiveKeywords.stream().filter(text::contains).count();
        if (hits >= 2) {
            return true;
        }
        return !effectiveKeywords.isEmpty() && (double) hits / effectiveKeywords.size() >= KEYWORD_SCORE_THRESHOLD;
    }

    private List<String> extractEffectiveKeywords(String queryText) {
        String cleaned = queryText.replaceAll("[的了吗呢吧啊呀哦么在是有被把给让跟和与及怎么什么如何多少可以能不能]", " ");
        cleaned = cleaned.replaceAll("[，。？！、；：\"'“”‘’（）《》【】\\s]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(part -> part.length() >= 2)
                .collect(Collectors.toList());
    }
```

- [ ] **Step 5: Run tests to verify pass**

Run:

```bash
./mvnw -Dtest=HybridContentRetrieverTest test
```

Expected: all `HybridContentRetrieverTest` tests pass.

---

### Task 3: Run broader retrieval tests and adjust only constants if needed

**Files:**
- Modify only if tests show ranking too weak: `src/main/java/org/example/consultant3/aiservice/config/HybridContentRetriever.java`
- Test: `src/test/java/org/example/consultant3/aiservice/config/KeywordContentRetrieverTest.java`
- Test: `src/test/java/org/example/consultant3/aiservice/config/HybridContentRetrieverTest.java`

- [ ] **Step 1: Run retriever unit tests**

Run:

```bash
./mvnw -Dtest=HybridContentRetrieverTest,KeywordContentRetrieverTest test
```

Expected: both test classes pass.

- [ ] **Step 2: If exact-query keyword results still do not rank high enough, adjust only the exact keyword weight**

Change this line in `weightsFor`:

```java
            case EXACT -> new RetrievalWeights(1.0, 1.5);
```

to:

```java
            case EXACT -> new RetrievalWeights(1.0, 1.8);
```

Then rerun:

```bash
./mvnw -Dtest=HybridContentRetrieverTest,KeywordContentRetrieverTest test
```

Expected: tests pass.

- [ ] **Step 3: If口语化噪声仍进入前排, adjust only the colloquial keyword weight**

Change this line in `weightsFor`:

```java
            case COLLOQUIAL -> new RetrievalWeights(1.0, 0.2);
```

to:

```java
            case COLLOQUIAL -> new RetrievalWeights(1.0, 0.1);
```

Then rerun:

```bash
./mvnw -Dtest=HybridContentRetrieverTest,KeywordContentRetrieverTest test
```

Expected: tests pass.

---

### Task 4: Verify existing evaluation path

**Files:**
- Read-only likely: existing evaluation scripts/results such as `batch_result*.json` or project-specific test runners.
- Modify only if a discovered test runner requires constants from Task 3.

- [ ] **Step 1: Locate evaluation command**

Check existing project files for evaluation runners or scripts. Prefer existing scripts/classes over creating a new evaluator.

Useful search targets:

```text
batch_result
R@20
P@5
Recall
Precision
评测
```

- [ ] **Step 2: Run the existing evaluation command**

Run the project’s existing evaluation command. If the command needs Docker services, start the existing services first:

```bash
docker start consultant-mysql redis-vector
```

Then run the discovered evaluation command.

Expected: output includes metrics for vector retrieval and hybrid retrieval.

- [ ] **Step 3: Compare vector vs hybrid metrics**

Record whether hybrid retrieval is higher than vector retrieval on the target metric. Preferred target order:

1. R@20 hybrid > R@20 vector
2. P@5 hybrid >= P@5 vector or materially improved from previous hybrid result
3. Overall answer quality does not regress on exact legal article queries

- [ ] **Step 4: If hybrid is not better, tune only rule constants**

Allowed tuning points:

```java
KEYWORD_SCORE_THRESHOLD
LAW_NAME_PATTERN
LEGAL_TERMS
case EXACT keyword weight
case LEGAL_TERM keyword weight
case COLLOQUIAL keyword weight
```

Do not rewrite the retrieval architecture during this test pass.

---

## Self-Review

- Spec coverage: dynamic query classification, dynamic weights, keyword admission filtering, preservation of existing chain, TDD, and evaluation are covered.
- Placeholder scan: no TBD/TODO/fill-later placeholders remain.
- Type consistency: tests use existing `HybridContentRetriever` constructor and LangChain4j `Query.from`; implementation adds private helpers only.
