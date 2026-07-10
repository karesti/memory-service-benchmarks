# LoCoMo Benchmark — Current State & Improvement Areas

## Current accuracy (gpt-4o-mini, conversation 0, 152 questions)

| Category | Correct | Total | Accuracy |
|----------|---------|-------|----------|
| causal | 10 | 13 | 76.9% |
| temporal | 23 | 37 | 62.2% |
| factual | 43 | 70 | 61.4% |
| multi-hop | 13 | 32 | 40.6% |
| **overall** | **89** | **152** | **58.6%** |

**Benchmark model**: gpt-4o-mini (answer generation + judging)
**Cognition model**: gpt-4o-mini (memory extraction)
**Search**: single vector query, top-k=50, 100 memories extracted per conversation

### Category descriptions

| Category | What it tests |
|----------|---------------|
| causal | Cause-and-effect reasoning — "What did X realize after Y happened?" |
| temporal | Time-based questions — "When did X do Y?" or "What happened first?" |
| factual | Direct fact recall — "Where does X live?" or "What is X's job?" |
| multi-hop | Combining multiple facts — "What hobbies do X's children share?" (find children → find each child's hobbies → intersect) |

---

## Failure Analysis (3 distinct patterns)

### Pattern 1: Cognition extraction lost specific details

**Affects: all categories — root cause of most factual and temporal failures**

The cognition processor over-summarizes when extracting memories. Specific facts, names, and dates are abstracted away. Once a detail is lost at extraction time, no amount of search or LLM tuning can recover it.

**Evidence from actual retrieved memories:**

| Question | Gold Answer | Top memory retrieved | Problem |
|----------|-------------|---------------------|---------|
| "Where did Caroline move from 4 years ago?" | Sweden | "has known her friends for four years since moving from her home country" | Country name lost — "home country" instead of "Sweden" |
| "When did Caroline have a picnic?" | The week before 6 July 2023 | "had a picnic with friends and family last week" | Absolute date lost — "last week" is useless without context |
| "What books has Melanie read?" | "Nothing is Impossible", "Charlotte's Web" | "engages in self-care activities such as running, reading, or playing the violin" | Book titles lost — only "reading" as generic activity survived |
| "What items has Melanie bought?" | Figurines, shoes | No relevant memory retrieved at all | Specific purchases never extracted as memories |
| "What are the new shoes that Melanie got used for?" | Running | No relevant memory retrieved at all | Shoe purchase never extracted |
| "What was Melanie's favorite childhood book?" | "Charlotte's Web" | No relevant memory retrieved at all | Childhood reading preference never extracted |
| "How many children does Melanie have?" | 3 | "Melanie has children" | Count lost — "has children" instead of "has 3 children" |
| "When did Melanie go to the museum?" | 5 July 2023 | No relevant memory retrieved at all | Museum visit never extracted as a memory |

**What needs to change in cognition extraction:**
1. Preserve specific entities verbatim — names, places, dates, numbers, book titles
2. Preserve temporal anchoring — compute absolute dates from relative references ("last week" → actual date)
3. Extract more granular facts — individual events, purchases, book titles should be separate memories, not collapsed into generic activity summaries
4. Extract causal chains as single memories — "After the charity race, Melanie realized self-care is important" should be one memory

### Pattern 2: Single vector search misses scattered facts

**Affects: mainly multi-hop**

When the answer requires aggregating information from multiple memories, a single vector search on the raw question doesn't retrieve all relevant pieces. The query embedding matches some memories but not others.

**Evidence:**

| Question | Gold Answer | What was retrieved | What was missed |
|----------|-------------|-------------------|-----------------|
| "What activities does Melanie partake in?" | pottery, camping, painting, swimming | running, reading, violin, hiking, painting, pottery | swimming — the query "activities" doesn't match a swimming memory semantically |
| "Where has Melanie camped?" | beach, mountains, forest | mountains, forest | beach — the camping memory didn't mention beach or it wasn't in top-50 |
| "What LGBTQ+ events has Caroline participated in?" | Pride parade, school speech, support group | Got pride and conference, missed school speech | school speech didn't match "LGBTQ+ events" semantically |
| "What is Caroline's relationship status?" | Single | No relevant memory retrieved | Relationship status was never directly stated or extracted |

**Root cause:** a single embedding of "What activities does Melanie partake in?" is closest to memories about activities in general, but individual activity mentions (swimming at a specific event) may be too far in embedding space.

### Pattern 3: Temporal references not resolved

**Affects: temporal questions**

Even when a memory exists, it often contains relative time references ("last week", "last month") instead of absolute dates, making temporal questions unanswerable.

**Evidence:**

| Question | Gold Answer | Memory content | Problem |
|----------|-------------|---------------|---------|
| "When did Caroline have a picnic?" | The week before 6 July 2023 | "had a picnic with friends last week" | "last week" relative to what? |
| "When did Caroline go to a pride parade during the summer?" | The week before 3 July 2023 | "attended a pride parade on July 8th, 2023" | Date was extracted but shifted (July 8 vs before July 3) |
| "When did Melanie go camping in July?" | Two weekends before 17 July 2023 | "went camping the week before June 27, 2023" | Memory exists but for June, not July — a second July camping event wasn't extracted |

---

## Available Infrastructure

### Multi-query semantic search (memory-service, main, PR #363)

The `/v1/memories/search` and `/admin/memories/search` endpoints accept a `queries` array (instead of a single `query`), each with `{text, purpose}`. Features:
- Results from each sub-query fused using **Reciprocal Rank Fusion (RRF, k=60)**
- Deduplication across sub-queries
- `matched_queries` attribution on each result
- Batched embedding (all sub-queries embedded in one call)
- `per_query_limit` to control results per sub-query

This is ready to use. The missing piece is: **who generates the sub-queries from the original question?**

### Hybrid search (memory-service, `hybrid_search` branch)

Vector + fulltext with RRF merge. Controlled by `MEMORY_SERVICE_SEARCH_HYBRID_ENABLED`. Previously tested with raw queries and lowered overall accuracy. Not yet benchmarked with current prompt + judge fixes.

---

## Improvement Priorities

| # | Improvement | Effort | Expected Impact | Status |
|---|-------------|--------|-----------------|--------|
| 1 | Cognition: preserve entities, dates, counts, causal chains | Medium | High (all categories) | To do — root cause of most failures |
| 2 | Query decomposition for multi-hop | Medium | High (multi-hop) | Multi-query search ready in memory-service. Decomposition logic not built yet — needs to live either in cognition or memory-service |
| 3 | Hybrid search | Low | TBD | Available in `hybrid_search` branch, not yet benchmarked |
