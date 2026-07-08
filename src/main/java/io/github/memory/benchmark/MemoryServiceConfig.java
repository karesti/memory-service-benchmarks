package io.github.memory.benchmark;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "memory-service")
public interface MemoryServiceConfig {

    @WithDefault("http://localhost:8082")
    String url();

    @WithDefault("agent-api-key-1")
    String apiKey();

    Oidc oidc();

    interface Oidc {

        @WithDefault("true")
        boolean enabled();

        @WithDefault("http://localhost:8081/realms/memory-service/protocol/openid-connect/token")
        String tokenUrl();

        @WithDefault("memory-service")
        String realm();

        @WithDefault("memory-service-client")
        String clientId();

        @WithDefault("change-me")
        String clientSecret();

        @WithDefault("benchmark")
        String userPassword();

        @WithDefault("true")
        boolean provisionUsers();

        @WithDefault("30")
        long refreshSkewSeconds();

        Admin admin();

        interface Admin {

            @WithDefault("http://localhost:8081")
            String serverUrl();

            @WithDefault("master")
            String realm();

            @WithDefault("admin-cli")
            String clientId();

            @WithDefault("admin")
            String username();

            @WithDefault("admin")
            String password();
        }
    }
}
