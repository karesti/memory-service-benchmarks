#!/bin/bash
# Run cognition processor with Ollama (free, local)
# Requires: ollama pull llama3.2
export MAVEN_OPTS="-Xmx10g"
cd ..
cd cognitive-memory/cognition-processor-quarkus
  ./mvnw clean
MEMORY_SERVICE_API_KEY=cognition-processor-key-123 \
  COGNITION_MEMORY_MODEL_PROVIDER=ollama \
  ./mvnw quarkus:dev
