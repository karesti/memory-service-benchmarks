export MAVEN_OPTS="-Xmx8g"
cd ..
cd memory-service
MEMORY_SERVICE_OPENAI_API_KEY=$OPENAI_API_KEY \
  MEMORY_SERVICE_MODE=testing \
  MEMORY_SERVICE_ROLES_ADMIN_CLIENTS="admin,turn_traces_processor,cognition_processor" \
  MEMORY_SERVICE_ROLES_INDEXER_CLIENTS="agent,cognition_processor" \
  MEMORY_SERVICE_API_KEYS_COGNITION_PROCESSOR=cognition-processor-key-123 \
  MEMORY_SERVICE_AIR_FULL_BIN="./bin/memory-service serve" \
  MEMORY_SERVICE_OIDC_ISSUER="" \
  MEMORY_SERVICE_SEARCH_HYBRID_ENABLED=false \
  MEMORY_SERVICE_OIDC_DISCOVERY_URL="" \
  task dev:memory-service