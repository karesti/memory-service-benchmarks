package io.github.memory.benchmark;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "benchmark")
public interface BenchmarkConfig {

    @WithDefault("datasets/locomo10.json")
    String dataset();

    @WithDefault("0,1,2,3,4,5,6,7,8,9")
    String conversations();

    @WithDefault("50")
    int topK();

    @WithDefault("results")
    String outputDir();

    Cognition cognition();

    HttpClient httpclient();

    interface Cognition {

        @WithDefault("true")
        boolean enabled();

        @WithDefault("cognition.v1")
        String namespace();

        @WithDefault("600")
        int waitTimeoutSeconds();

        @WithDefault("10")
        int pollIntervalSeconds();

        @WithDefault("90")
        int stableSeconds();
    }

    interface HttpClient {

        @WithDefault("30")
        int connectionTimeout();
    }
}
