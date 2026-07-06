#!/bin/bash
# Run memory-service fully local: local embeddings + Infinispan (cache + vector) + PostgreSQL
# No Redis, no Qdrant, no OpenAI — single Infinispan container for both cache and vector
# Requires: fresh database (docker compose down -v) when switching from OpenAI/Qdrant setup
podman rm -f $(podman ps -a -q)
cd ..
cd memory-service
rm -f /bin/memory-service
MEMORY_SERVICE_AIR_FULL_BIN="./bin/memory-service serve" \
  MEMORY_SERVICE_OIDC_ISSUER="" \
  MEMORY_SERVICE_OIDC_DISCOVERY_URL="" \
  MEMORY_SERVICE_SEARCH_HYBRID_ENABLED=true \
  task dev:memory-service-infinispan
