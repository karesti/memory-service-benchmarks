# Memory Service Benchmarks

LoCoMo benchmark harness for evaluating the memory-service cognition pipeline.

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

## Running the benchmark

### Mode 1: With cognition (full pipeline)

Tests: ingest conversations → cognition processor extracts memories → semantic search → LLM answers → LLM judges.

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

**Terminal 2 — Cognition processor:**

```bash
cd cognitive-memory/cognition-processor-quarkus
MEMORY_SERVICE_API_KEY=cognition-processor-key-123 \
MEMORY_MODEL_PROVIDER=openai \
MEMORY_MODEL_ID=gpt-4o-mini \
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_MODEL_NAME=gpt-4o-mini \
./mvnw quarkus:dev
```

**Terminal 3 — Benchmark:**

```bash
cd memory-service-benchmarks
java -Xmx2g -Dbenchmark.conversations=0 -jar target/quarkus-app/quarkus-run.jar
```

### Mode 2: Without cognition (substrate only)

Skip terminal 2 (no cognition processor). Run with cognition disabled:

```bash
java -Xmx2g -Dbenchmark.conversations=0 -Dbenchmark.cognition.enabled=false \
  -jar target/quarkus-app/quarkus-run.jar
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
```

Example output:

```
╔══════════════════════════════════════════════════╗
║          LoCoMo Benchmark Results                ║
╠══════════════════════════════════════════════════╣
║  Overall Accuracy:  16.4% (25/152)              ║
║  Avg Search Latency: 251 ms                     ║
║  Avg Memories/Query: 50.0                        ║
╠══════════════════════════════════════════════════╣
║  Category Breakdown:                             ║
║    multi-hop       15.6% (5/32)                  ║
║    temporal         2.7% (1/37)                  ║
║    causal           7.7% (1/13)                  ║
║    factual         25.7% (18/70)                 ║
╚══════════════════════════════════════════════════╝
```

## Configuration

All settings in `src/main/resources/application.properties`, using `@ConfigMapping`:

| Property | Default | Description |
|---|---|---|
| `memory-service.url` | `http://localhost:8082` | Memory service URL |
| `memory-service.api-key` | `agent-api-key-1` | API key for authentication |
| `benchmark.dataset` | `datasets/locomo10.json` | Path to LoCoMo dataset |
| `benchmark.conversations` | `0,1,2,3,4,5,6,7,8,9` | Which conversations to run |
| `benchmark.top-k` | `50` | Max memories to retrieve per question |
| `benchmark.output-dir` | `results` | Output directory |
| `benchmark.cognition.enabled` | `true` | Wait for cognition processor |
| `benchmark.cognition.namespace` | `cognition.v1` | Cognition memory namespace |
| `benchmark.cognition.wait-timeout-seconds` | `600` | Max wait for extraction |
| `benchmark.cognition.poll-interval-seconds` | `10` | Polling interval |
| `benchmark.cognition.stable-seconds` | `90` | Seconds of no new memories before proceeding |
| `benchmark.httpclient.connection-timeout` | `30` | HTTP connection timeout |

Override any property with `-D`:

```bash
java -Xmx2g -Dbenchmark.conversations=0,1 -Dbenchmark.top-k=20 -jar target/quarkus-app/quarkus-run.jar
```

## Architecture

```
LoCoMo dataset (10 conversations, ~200 QA)
        │
        ▼
┌──────────────────────────┐
│  LoCoMoBenchmark (main)  │
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

## What LoCoMo tests

LoCoMo (ACL 2024) provides 10 multi-session conversations with ~200 QA pairs across 5 categories:

| Category | Tests |
|---|---|
| 1 - Multi-hop | Connecting facts across sessions |
| 2 - Temporal | Dates, timing, sequences |
| 3 - Causal | Reasoning about why things happened |
| 4 - Factual | Direct fact recall |
| 5 - Adversarial | Questions about things never discussed (skipped) |
