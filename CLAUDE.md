# Memory Service Benchmarks

Quarkus CLI app that benchmarks the memory-service + cognition processor pipeline using industry-standard datasets.

## Supported Benchmarks

- **LoCoMo** (ACL 2024): 10 multi-session conversations, ~200 QA pairs, 5 categories
- **LongMemEval** (ICLR 2025): 500 independent questions, 6 types, each with its own conversation history (~53 sessions)

## Project structure

```
src/main/java/io/github/memory/benchmark/
├── BenchmarkCommand.java       # @TopCommand — picocli dispatcher (locomo | longmemeval)
├── BenchmarkConfig.java        # @ConfigMapping for benchmark.* properties
├── MemoryServiceConfig.java    # @ConfigMapping for memory-service.* properties
├── MemoryServiceClient.java    # REST client: create conversations, append entries, search memories, wait for cognition
├── LlmAnswerGenerator.java     # LangChain4j @RegisterAiService — generates answers from retrieved memories
├── LlmJudge.java               # LangChain4j @RegisterAiService — judges answer correctness (CORRECT/WRONG)
├── BenchmarkResult.java        # Per-question result record (shared across benchmarks)
├── MetricsReport.java          # Accuracy computation and formatting (per-category breakdown)
├── locomo/
│   ├── LoCoMoBenchmark.java    # locomo subcommand — ingest → wait → search → answer → judge
│   └── LoCoMoDataset.java      # Parses locomo10.json
└── longmemeval/
    ├── LongMemEvalBenchmark.java  # longmemeval subcommand — per-question ingest → search → answer → judge
    └── LongMemEvalDataset.java    # Parses longmemeval_s_cleaned.json (500 questions, 6 types)
```

## How it works

### LoCoMo
1. Ingests conversation turns into memory-service as entries
2. Waits for cognition processor to extract memories (polls until stable)
3. For each question: searches cognition memories, generates answer, judges correctness
4. Computes per-category accuracy

### LongMemEval
1. For each question: ingests that question's ~53 haystack sessions into memory-service
2. Waits for cognition processor (if enabled)
3. Searches memories, generates answer, judges correctness
4. Computes per-question-type accuracy
5. Supports stratified sampling (default 5 per type = 30 questions)

## Key patterns

- **Config**: Quarkus `@ConfigMapping` interfaces (`BenchmarkConfig`, `MemoryServiceConfig`)
- **CLI**: Picocli subcommands — `locomo` and `longmemeval`
- **LLM services**: `@RegisterAiService` with `NoChatMemoryProviderSupplier` and `@V` annotations
- **HTTP client**: `java.net.http.HttpClient` for memory-service calls
- **Auth**: `X-API-Key` for client identity + `Authorization: Bearer {userId}` for user scoping

## Build and run

```bash
./mvnw clean package -DskipTests

# LoCoMo — single conversation
java -Xmx2g -Dbenchmark.conversations=0 -jar target/quarkus-app/quarkus-run.jar locomo

# LoCoMo — all conversations
java -Xmx2g -jar target/quarkus-app/quarkus-run.jar locomo

# LongMemEval — sampled (5 per type = 30 questions)
java -Xmx2g -jar target/quarkus-app/quarkus-run.jar longmemeval

# LongMemEval — 2 per type (quick smoke test)
java -Xmx2g -Dbenchmark.longmemeval.per-type=2 -jar target/quarkus-app/quarkus-run.jar longmemeval

# LongMemEval — all 500 questions
java -Xmx2g -Dbenchmark.longmemeval.per-type=0 -jar target/quarkus-app/quarkus-run.jar longmemeval

# Without cognition (stop cognition processor first)
java -Xmx2g -Dbenchmark.cognition.enabled=false -jar target/quarkus-app/quarkus-run.jar locomo
```

## Dependencies on other projects

- **memory-service** (`../memory-service`): Must be running on port 8082 with OpenAI embeddings enabled
- **cognition-processor** (`../cognitive-memory/cognition-processor-quarkus`): Must be running for cognition mode
- **memory-service-rest-quarkus**: Maven dependency (999-SNAPSHOT) — installed in local Maven repo
- **LoCoMo dataset**: Symlinked from `../locomo/data/locomo10.json`
- **LongMemEval dataset**: Symlinked from `../LongMemEval/data/longmemeval_s_cleaned.json`

## Gotchas

- The cognition processor must set the `index` field on `PutMemoryRequest` for vector search to work.
- Memory-service needs `MEMORY_SERVICE_OPENAI_API_KEY` for embedding generation.
- `MEMORY_SERVICE_AIR_FULL_BIN="./bin/memory-service serve"` skips the Delve debugger on Apple Silicon.
- The cognition processor's debounce delay (~1 min) + LLM extraction time means 3-5 min before memories appear.
- After resetting the database (`docker compose down -v`), you need a full re-run.
- LongMemEval has 500 questions with ~53 sessions each — a full run takes many hours. Use `--per-type` for quick tests.
