# Memory Service Benchmarks

Benchmark harness for evaluating the memory-service cognition pipeline using industry-standard datasets.

## Supported Benchmarks

- **LoCoMo** (ACL 2024): 10 multi-session conversations, ~200 QA pairs, 5 categories
- **LongMemEval** (ICLR 2025): 500 independent questions, 6 types, each with its own conversation history

## Prerequisites

- Java 21+
- Docker and Docker Compose
- [Task](https://taskfile.dev/) (`brew install go-task`)
- [air](https://github.com/air-verse/air) (`go install github.com/air-verse/air@latest` — ensure `~/go/bin` is in PATH)
- An OpenAI API key (for embeddings, cognition extraction, and the benchmark LLM judge)

## Setup (one time)

### 1. Configure the memory-service for cognition

From the `memory-service/` directory:

```bash
cd ../memory-service
cp ../cognitive-memory/memory-service/compose.override.yaml.example ./compose.override.yaml
```

### 2. Install the memory-service REST client

```bash
cd ../memory-service
./java/mvnw -f java/pom.xml -pl quarkus/memory-service-rest-quarkus -am install -DskipTests
```

### 3. Set your OpenAI API key

Add to your `~/.zshrc`:

```bash
export OPENAI_API_KEY=sk-...
export PATH=$PATH:$HOME/go/bin
```

Then `source ~/.zshrc`.

### 4. Build the benchmark

```bash
cd memory-service-benchmarks
./mvnw clean package -DskipTests
```

## Running the benchmarks

### Start the services

**Terminal 1 — Memory service:**

```bash
cd memory-service
MEMORY_SERVICE_OPENAI_API_KEY=$OPENAI_API_KEY \
MEMORY_SERVICE_ROLES_ADMIN_CLIENTS="admin,turn_traces_processor,cognition_processor" \
MEMORY_SERVICE_ROLES_INDEXER_CLIENTS="agent,cognition_processor" \
MEMORY_SERVICE_API_KEYS_COGNITION_PROCESSOR=cognition-processor-key-123 \
MEMORY_SERVICE_AIR_FULL_BIN="./bin/memory-service serve" \
task dev:memory-service
```

**Terminal 2 — Cognition processor** (skip for no-cognition runs):

```bash
cd cognitive-memory/cognition-processor-quarkus
MEMORY_SERVICE_API_KEY=cognition-processor-key-123 \
MEMORY_MODEL_PROVIDER=openai \
MEMORY_MODEL_ID=gpt-4o-mini \
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_MODEL_NAME=gpt-4o-mini \
./mvnw quarkus:dev
```

### LoCoMo

```bash
# Single conversation (quick test)
java -Xmx2g -Dbenchmark.conversations=0 -jar target/quarkus-app/quarkus-run.jar locomo

# All 10 conversations
java -Xmx2g -jar target/quarkus-app/quarkus-run.jar locomo

# Without cognition (stop cognition processor first)
java -Xmx2g -Dbenchmark.cognition.enabled=false -jar target/quarkus-app/quarkus-run.jar locomo
```

### LongMemEval

```bash
# Quick smoke test (2 per type = 12 questions)
java -Xmx2g -Dbenchmark.longmemeval.per-type=2 -jar target/quarkus-app/quarkus-run.jar longmemeval

# Default (5 per type = 30 questions)
java -Xmx2g -jar target/quarkus-app/quarkus-run.jar longmemeval

# All 500 questions (takes many hours)
java -Xmx2g -Dbenchmark.longmemeval.per-type=0 -jar target/quarkus-app/quarkus-run.jar longmemeval

# Filter by question type
java -Xmx2g -Dbenchmark.longmemeval.question-types=temporal-reasoning,multi-session \
  -jar target/quarkus-app/quarkus-run.jar longmemeval

# Without cognition
java -Xmx2g -Dbenchmark.cognition.enabled=false -jar target/quarkus-app/quarkus-run.jar longmemeval
```

### Clean run (reset database)

To start fresh between runs:

```bash
cd memory-service
docker compose down -v
docker compose up -d qdrant postgres redis keycloak prometheus minio minio-init clickhouse langfuse-worker langfuse-web
```

Then restart memory-service and cognition processor.

## Results

Results are written to `results/` as JSON:

```
results/locomo_cognition_2026-06-25T08-27-19.json
results/locomo_substrate_2026-06-25T08-30-00.json
results/longmemeval_cognition_2026-06-25T10-15-00.json
```

## Configuration

All settings in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `memory-service.url` | `http://localhost:8082` | Memory service URL |
| `memory-service.api-key` | `agent-api-key-1` | API key for authentication |
| `benchmark.top-k` | `50` | Max memories to retrieve per question |
| `benchmark.output-dir` | `results` | Output directory |
| `benchmark.cognition.enabled` | `true` | Wait for cognition processor |
| `benchmark.cognition.namespace` | `cognition.v1` | Cognition memory namespace |
| `benchmark.cognition.wait-timeout-seconds` | `600` | Max wait for extraction |
| `benchmark.cognition.poll-interval-seconds` | `10` | Polling interval |
| `benchmark.cognition.stable-seconds` | `90` | Seconds of stability before proceeding |

**LoCoMo-specific:**

| Property | Default | Description |
|---|---|---|
| `benchmark.dataset` | `datasets/locomo10.json` | Path to LoCoMo dataset |
| `benchmark.conversations` | `0,1,2,3,4,5,6,7,8,9` | Which conversations to run |

**LongMemEval-specific:**

| Property | Default | Description |
|---|---|---|
| `benchmark.longmemeval.dataset` | `datasets/longmemeval_s_cleaned.json` | Path to LongMemEval dataset |
| `benchmark.longmemeval.per-type` | `5` | Questions per type (0 = all) |
| `benchmark.longmemeval.seed` | `42` | Random seed for sampling |
| `benchmark.longmemeval.question-types` | _(all)_ | Comma-separated type filter |

Override any property with `-D`:

```bash
java -Xmx2g -Dbenchmark.top-k=20 -jar target/quarkus-app/quarkus-run.jar locomo
```

## Architecture

```
Dataset (LoCoMo / LongMemEval)
        │
        ▼
┌──────────────────────────┐
│  Benchmark Runner        │
│  1. Ingest conversations │──→  Memory Service (REST API :8082)
│  2. Wait for cognition   │         │
│  3. Search memories      │         ▼
│  4. LLM answers          │     Cognition Processor (Quarkus :8090)
│  5. LLM judges           │     extracts facts, preferences, procedures
│  6. Compute metrics      │         │
└──────────────────────────┘         ▼
        │                    Memory Service stores extracted memories
        ▼                    under ["user", userId, "cognition.v1", ...]
   results/*.json
```

## What the benchmarks test

### LoCoMo (ACL 2024)

10 multi-session conversations with ~200 QA pairs across 5 categories:

| Category | Tests |
|---|---|
| Multi-hop | Connecting facts across sessions |
| Temporal | Dates, timing, sequences |
| Causal | Reasoning about why things happened |
| Factual | Direct fact recall |
| Adversarial | Questions about things never discussed (skipped) |

### LongMemEval (ICLR 2025)

500 questions across 6 types, each with its own conversation history (~53 sessions):

| Question Type | Tests |
|---|---|
| single-session-user | Facts stated by the user in one session |
| single-session-assistant | Information provided by the assistant |
| single-session-preference | User preferences expressed in one session |
| temporal-reasoning | Time-based reasoning across sessions |
| knowledge-update | Updated facts that override earlier ones |
| multi-session | Connecting information across multiple sessions |
