# Memory Service Benchmarks

Quarkus CLI app that benchmarks the memory-service + cognition processor pipeline using industry-standard datasets (starting with LoCoMo).

## Project structure

```
src/main/java/io/github/memory/benchmark/
├── BenchmarkConfig.java        # @ConfigMapping for benchmark.* properties
├── MemoryServiceConfig.java    # @ConfigMapping for memory-service.* properties
├── MemoryServiceClient.java    # REST client: create conversations, append entries, search memories, wait for cognition
├── LlmAnswerGenerator.java     # LangChain4j @RegisterAiService — generates answers from retrieved memories
├── LlmJudge.java               # LangChain4j @RegisterAiService — judges answer correctness (CORRECT/WRONG)
├── BenchmarkResult.java        # Per-question result record
├── MetricsReport.java          # Accuracy computation and formatting (per-category breakdown)
└── locomo/
    ├── LoCoMoBenchmark.java    # @QuarkusMain — main runner: ingest → wait → search → answer → judge
    └── LoCoMoDataset.java      # Parses locomo10.json (conversations, sessions, turns, QA pairs)
```

## How it works

1. Parses the LoCoMo dataset (10 multi-session conversations, ~200 QA pairs)
2. Ingests conversation turns into memory-service as entries via REST API
3. Waits for the cognition processor to extract memories (polls until stable)
4. For each question: searches cognition memories via `/v1/memories/search`, generates an answer with an LLM, judges correctness with LLM-as-judge
5. Computes per-category accuracy and writes results JSON

## Key patterns

- **Config**: Uses Quarkus `@ConfigMapping` interfaces (`BenchmarkConfig`, `MemoryServiceConfig`), not `@ConfigProperty`
- **LLM services**: Uses `@RegisterAiService` with `NoChatMemoryProviderSupplier` and `@V` annotations on method parameters
- **HTTP client**: Uses `java.net.http.HttpClient` directly (not the Quarkus REST client) for memory-service calls
- **Auth**: `X-API-Key` header for client identity + `Authorization: Bearer {userId}` for user scoping

## Build and run

```bash
./mvnw clean package -DskipTests
java -Xmx2g -Dbenchmark.conversations=0 -jar target/quarkus-app/quarkus-run.jar
```

## Dependencies on other projects

- **memory-service** (`../memory-service`): Must be running on port 8082 with OpenAI embeddings enabled (`MEMORY_SERVICE_OPENAI_API_KEY`)
- **cognition-processor** (`../cognitive-memory/cognition-processor-quarkus`): Must be running for cognition mode, uses OpenAI (`MEMORY_MODEL_PROVIDER=openai`)
- **memory-service-rest-quarkus**: Maven dependency (`io.github.chirino.memory-service:memory-service-rest-quarkus:999-SNAPSHOT`) — must be installed in local Maven repo
- **LoCoMo dataset**: Symlinked from `../locomo/data/locomo10.json` into `datasets/`

## Gotchas

- The cognition processor must set the `index` field on `PutMemoryRequest` for vector search to work. Without it, memories exist but semantic search returns empty results.
- Memory-service needs `MEMORY_SERVICE_OPENAI_API_KEY` set for embedding generation. The Taskfile doesn't set embedding base URL/model, so it defaults to OpenAI's `text-embedding-3-small`.
- When running `task dev:memory-service`, the env vars from the Taskfile override `.env` — pass extra config as env vars on the command line.
- `MEMORY_SERVICE_AIR_FULL_BIN="./bin/memory-service serve"` skips the Delve debugger which fails on Apple Silicon.
- The cognition processor's debounce delay (1 min) + LLM extraction time means ~3-5 minutes before memories start appearing after ingestion.
- After resetting the database (`docker compose down -v`), all memories and conversations are lost — you need a full re-run.
- Category 5 (adversarial/unanswerable) questions are skipped in the current implementation.

## First benchmark result (conversation 0, gpt-4o-mini)

```
Overall: 16.4% (25/152)
  multi-hop:  15.6% (5/32)
  temporal:    2.7% (1/37)
  causal:      7.7% (1/13)
  factual:    25.7% (18/70)
```

Temporal scores low because the cognition processor doesn't preserve dates/timestamps in extracted memories.
