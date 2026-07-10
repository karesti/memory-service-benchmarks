# LoCoMo Benchmark — Current State & Improvement Areas

## Current accuracy (gpt-4o-mini, conversation 0, 152 questions)

| Category | Correct | Total | Accuracy |
|----------|---------|-------|----------|
| factual | 55 | 70 | 78.6% |
| causal | 10 | 13 | 76.9% |
| temporal | 24 | 37 | 64.9% |
| multi-hop | 20 | 32 | 62.5% |
| **overall** | **109** | **152** | **71.7%** |

**Benchmark model**: gpt-4o-mini (answer generation + judging)
**Cognition model**: gpt-4o-mini (memory extraction)
**Search**: single vector query, top-k=100, 347 memories extracted per conversation

### Results progression

| Run | Overall | Factual | Causal | Temporal | Multi-hop | What changed |
|-----|---------|---------|--------|----------|-----------|--------------|
| Baseline | 58.6% | 61.4% | 76.9% | 62.2% | 40.6% | Prompt + judge fixes, top-k=50, ~133 memories |
| + Exhaustive extraction | 61.2% | 78.6% | 69.2% | 45.9% | 37.5% | Extract everything, top-k=50 — factual +17pp but temporal regressed due to search window too small for 347 memories |
| + top-k=100 | **71.7%** | **78.6%** | **76.9%** | **64.9%** | **62.5%** | top-k=100 recovered temporal and unlocked multi-hop +22pp |

### Category descriptions

| Category | What it tests |
|----------|---------------|
| factual | Direct fact recall — "Where does X live?" or "What is X's job?" |
| causal | Cause-and-effect reasoning — "What did X realize after Y happened?" |
| temporal | Time-based questions — "When did X do Y?" or "What happened first?" |
| multi-hop | Combining multiple facts — "What hobbies do X's children share?" (find children → find each child's hobbies → intersect) |

---

## What changed to get from 58.6% to 71.7%

### 1. Exhaustive extraction (cognition processor)

Rewrote the extraction prompt from "extract durable information" to "extract EVERYTHING." Every event, item, feeling, plan, detail — no matter how minor. This increased extracted memories from ~133 to 347, and is the main driver of the factual +17pp improvement.

Key changes in `durable-extractor-system.md`:
- Philosophy changed from selective/durable to exhaustive
- Anti-generalization rule: never replace "Sweden" with "home country", "Charlotte's Web" with "a book", etc.
- Temporal resolution: resolve "last week" to absolute dates using entry timestamps
- One fact per memory: don't combine unrelated facts

### 2. Relaxed verifier (cognition processor)

Changed citation verification from "exact match only" to substring matching. Citations that omit the `[timestamp] [ROLE]` prefix are now valid. This prevents good memories from being rejected due to formatting mismatches.

### 3. Increased max-completion-tokens (cognition processor)

Bumped from 4096 to 8192 to prevent JSON truncation with larger extractions.

### 4. Increased top-k from 50 to 100 (benchmark)

With 347 memories, top-k=50 only covered 14% of all memories per question. Temporal and multi-hop questions need broader coverage. top-k=100 recovered temporal (+19pp from the regression) and unlocked multi-hop (+25pp).

---

## Available Infrastructure

### Multi-query semantic search (memory-service, main, PR #363)

The `/v1/memories/search` and `/admin/memories/search` endpoints accept a `queries` array (instead of a single `query`), each with `{text, purpose}`. Features:
- Results from each sub-query fused using **Reciprocal Rank Fusion (RRF, k=60)**
- Deduplication across sub-queries
- `matched_queries` attribution on each result
- Batched embedding (all sub-queries embedded in one call)
- `per_query_limit` to control results per sub-query

### Query decomposition (cognition processor + memory-service, built, not yet tested)

- Cognition processor: `POST /api/decompose` endpoint — LLM decomposes complex questions into sub-queries, passes simple ones through unchanged
- Memory-service: when `MEMORY_SERVICE_COGNITION_PROCESSOR_URL` is set, automatically decomposes single queries and uses multi-query search if multiple sub-queries are returned. Falls back to single query on failure.
- **Status**: code is built but hostname was wrong in compose.yaml (`cognition` → should be `cognition-processor`). Not yet tested with correct config.

### Hybrid search (memory-service, `hybrid_search` branch)

Vector + fulltext with RRF merge. Controlled by `MEMORY_SERVICE_SEARCH_HYBRID_ENABLED`. Not yet benchmarked with current setup.

---

## Remaining failures

### Still WRONG — multi-hop (12/32 wrong)
- "Where did Caroline move from 4 years ago?" — "Sweden" still extracted as "home country" in one memory
- "How many children does Melanie have?" — extracted as "two children" instead of 3
- "What activities does Melanie partake in?" — incomplete list
- "What LGBTQ+ events has Caroline participated in?" — misses some events
- These need query decomposition to improve further

### Still WRONG — temporal (13/37 wrong)
- Several "When did X happen?" questions where the extracted date is wrong or missing
- "When did Caroline have a picnic?" — date still not correctly resolved

### Still WRONG — factual (15/70 wrong)
- Very specific details: "What precautionary sign at the café?", "What did the posters say?", "What painting on October 13?"
- These may need even more granular extraction or are at the limit of what gpt-4o-mini can extract

---

## Next steps

| # | Improvement | Effort | Expected Impact | Status |
|---|-------------|--------|-----------------|--------|
| 1 | Test query decomposition | Low | Multi-hop improvement | Built, needs hostname fix + test |
| 2 | Try gpt-4o for extraction | Low | Better extraction quality | Config change only |
| 3 | Hybrid search | Low | TBD | Available in `hybrid_search` branch |
