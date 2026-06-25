package io.github.memory.benchmark;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "memory-service")
public interface MemoryServiceConfig {

    @WithDefault("http://localhost:8082")
    String url();

    @WithDefault("agent-api-key-1")
    String apiKey();
}
