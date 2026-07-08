# LoCoMo Benchmark — Improvement Areas

## Current Baseline (gpt-4o-mini, 2026-07-08)

| Metric | Value |
|--------|-------|
| **LLM Judge Accuracy** | **53.3%** (81/152) |
| F1 Score | 24.4% |
| BLEU Score | 5.7% |

**Metrics explained:**
- **LLM Judge Accuracy** (primary metric): a separate LLM acts as judge — given the question, the gold answer, and the generated answer, it returns CORRECT or WRONG. It evaluates semantically, so it understands that "Single" and "Not in a relationship" mean the same thing. This is the most reliable metric because it mirrors how a human would evaluate the answer.
- **F1 Score**: word overlap between generated and gold answers (precision × recall). Doesn't understand meaning — "Sweden" vs "Not in a relationship" scores 0 even if both are correct paraphrases.
- **BLEU Score**: n-gram (word sequence) overlap. Stricter than F1 because word order matters. Originally designed for machine translation evaluation.

| Category | Accuracy |
|----------|----------|
| causal | 76.9% (10/13) |
| temporal | 64.9% (24/37) |
| factual | 57.1% (40/70) |
| **multi-hop** | **21.9% (7/32)** |

---

## Priority 1: Cognition Extraction Losing Critical Details

**Impact: High — root cause of many failures across all categories**

The cognition processor over-summarizes when extracting memories from conversation entries. Specific facts are abstracted away, making them unretrievable regardless of search or model quality.

### Examples from benchmark results

| Question | Gold Answer | What Cognition Extracted | Problem |
|----------|-------------|--------------------------|---------|
| "Where did Caroline move from 4 years ago?" | Sweden | "moved from her home country" | Country name lost |
| "What did Melanie realize after the charity race?" | self-care is important | Two separate memories: one for the race, one for self-care | Causal link broken |
| "When is Melanie planning on going camping?" | June 2023 | "went camping... during the week before June 27, 2023" | Memory exists but temporal framing changed |

### Recommended changes

1. **Preserve specific entities** — names, places, dates, and numbers must survive extraction verbatim. "Her home country" is useless without "Sweden".
2. **Extract causal chains as single memories** — "After the charity race, Melanie realized self-care is important" should be one memory, not two disconnected ones. This directly affects multi-hop (21.9%) and causal (46.2%) categories.
3. **Preserve temporal anchoring** — keep both absolute dates and relative references ("the week before June 9") when present in the source conversation.

---

## Priority 2: Query-Side Processing (Query Decomposition)

**Impact: High for multi-hop (21.9%), limited for other categories**

There is an asymmetry in the pipeline: cognition processes the *input* side (conversation → extracted memories), but the *query* side sends raw questions directly to vector search with no processing.

This matters most for **multi-hop questions** that require combining information from multiple sources. For example:

> "What hobbies do X's children share?"

This requires three separate lookups:
1. Who are X's children?
2. What are each child's hobbies?
3. What's the intersection?

A single vector search cannot do this effectively.

### Recommended approach

- **Query decomposition**: break complex questions into sub-queries, search for each independently, then combine results before sending to the answer generator.
- This is specifically valuable for multi-hop questions. Simpler factual/temporal questions don't benefit as much since a single search usually finds the relevant memory.

---

## Revisit: Hybrid Search (Vector + Fulltext)

Hybrid search was tested with raw queries and **lowered accuracy** with gpt-4o-mini compared to vector-only search. Fulltext matches on unprocessed questions introduce noisy/irrelevant results that dilute retrieval quality.

However, hybrid search could become effective **if combined with query-side cognition** (Priority 3). The problem isn't hybrid search itself — it's sending raw unprocessed questions to it. With query decomposition, the fulltext component would receive precise entity/keyword terms (e.g., "Sweden", "Caroline", "camping June 2023") that fulltext excels at matching exactly. This would complement vector search rather than adding noise.

**Recommendation:** revisit hybrid search after implementing query-side processing (Priority 2).

---

## Summary

| # | Improvement | Effort | Expected Impact | Status |
|---|-------------|--------|-----------------|--------|
| 1 | Cognition: preserve entities, causal chains, dates | Medium | High (all categories) | To do |
| 2 | Query decomposition for multi-hop | Medium | High (multi-hop) | To do |
| 3 | Hybrid search (after query-side cognition) | Low | Medium | Revisit after #2 |
