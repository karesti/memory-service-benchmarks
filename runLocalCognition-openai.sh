#!/bin/bash
# Run cognition processor with OpenAI (paid, best quality)
# Models: gpt-4o-mini (cheap), gpt-4o (better), gpt-5.4-mini (best cheap), gpt-5.4 (best)
export MAVEN_OPTS="-Xmx10g"
cd ..
cd cognitive-memory/cognition-processor-quarkus
MEMORY_SERVICE_API_KEY=cognition-processor-key-123 \
  COGNITION_MEMORY_MODEL_PROVIDER=openai \
  COGNITION_OPENAI_API_KEY=$OPENAI_API_KEY \
  COGNITION_OPENAI_MODEL_NAME=gpt-4o-mini \
  ./mvnw quarkus:dev
