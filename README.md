# Memory Service Benchmarks

LoCoMo benchmark harness for evaluating the memory-service cognition pipeline.

## Prerequisites

- Java 21+
- Docker and Docker Compose
- [Ollama](https://ollama.ai) (for embeddings)
- An OpenAI API key (for the LLM judge)

## Setup (one time)

### 1. Pull the embedding model for Ollama

```bash
ollama pull nomic-embed-text
```

### 2. Configure the memory-service for cognition

From the `memory-service/` directory:

```bash
cd ../memory-service
cp ../cognitive-memory/memory-service/compose.override.yaml.example ./compose.override.yaml
cp ../cognitive-memory/memory-service/.env.example ./.env
```

This configures:
- Ollama as the embedding provider (`nomic-embed-text`)
- The `cognition_processor` API key and admin role
- Docker network access to host Ollama

### 3. Install the memory-service REST client (if not already done)

```bash
cd ../memory-service
./java/mvnw -f java/pom.xml -pl quarkus/memory-service-rest-quarkus -am install -DskipTests
```

### 4. Set your OpenAI API key

```bash
export OPENAI_API_KEY=sk-...
```

This is used by the benchmark's LLM judge (answer generation + correctness scoring).
It is NOT used by the memory-service or cognition processor.

## Running the benchmark

### Mode 1: With cognition (full pipeline)

This tests: conversations ingested → cognition processor extracts memories → search returns extracted memories → LLM answers from memories.

**Terminal 1 — Start the infrastructure + memory-service:**

```bash
cd ../memory-service
task dev:memory-service
```

Wait until you see `Memory Service started on port 8082`.

**Terminal 2 — Start the cognition processor:**

```bash
cd ../cognitive-memory/cognition-processor-quarkus
./mvnw quarkus:dev
```

Wait until you see it connected to the event stream.

**Terminal 3 — Run the benchmark:**

```bash
cd memory-service-benchmarks
./mvnw quarkus:run
```

To test with just one conversation first (faster):

```bash
./mvnw quarkus:run -Dbenchmark.conversations=0
```

### Mode 2: Without cognition (substrate only)

This tests: conversations ingested → search directly on conversation entries → no extracted memories.

**Terminal 1 — Start the infrastructure + memory-service** (same as above):

```bash
cd ../memory-service
task dev:memory-service
```

**Terminal 2 — Run the benchmark without cognition:**

```bash
cd memory-service-benchmarks
./mvnw quarkus:run -Dbenchmark.cognition.enabled=false
```

No need to start the cognition processor.

## Results

Results are written to `results/` as JSON:

```
results/locomo_cognition_2026-06-22T15-30-00.json    # with cognition
results/locomo_substrate_2026-06-22T15-35-00.json     # without cognition
```

Each file contains:
- Per-question results (question, generated answer, verdict, search latency, memories retrieved)
- Per-category accuracy (multi-hop, temporal, causal, factual)
- Overall accuracy and latency metrics

## Configuration

All settings are in `src/main/resources/application.properties`:

| Property | Default | Description |
|---|---|---|
| `memory-service.url` | `http://localhost:8082` | Memory service URL |
| `memory-service.api-key` | `agent-api-key-1` | API key for authentication |
| `benchmark.dataset` | `datasets/locomo10.json` | Path to LoCoMo dataset |
| `benchmark.conversations` | `0,1,2,3,4,5,6,7,8,9` | Which conversations to run |
| `benchmark.top-k` | `50` | Max memories to retrieve per question |
| `benchmark.cognition.enabled` | `true` | Wait for cognition processor |
| `benchmark.cognition.wait-timeout-seconds` | `600` | Max wait for extraction |
| `benchmark.output-dir` | `results` | Output directory |

Override any property with `-D`:

```bash
./mvnw quarkus:run -Dbenchmark.conversations=0,1 -Dbenchmark.top-k=20
```

## Architecture

```
LoCoMo dataset (10 conversations, ~200 QA)
        │
        ▼
┌─────────────────────────┐
│  LocomoBenchmark (main)  │
│  1. Ingest conversations │──→  Memory Service (REST API :8082)
│  2. Wait for cognition   │         │
│  3. Search memories      │         ▼
│  4. LLM answers          │     Cognition Processor (Quarkus :8090)
│  5. LLM judges           │     extracts facts, preferences, procedures
│  6. Compute metrics      │         │
└─────────────────────────┘         ▼
        │                    Memory Service stores extracted memories
        ▼                    under ["user", userId, "cognition.v1", ...]
   results/*.json
```
