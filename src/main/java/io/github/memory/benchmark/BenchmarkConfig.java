package io.github.memory.benchmark;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "benchmark")
public interface BenchmarkConfig {

    @WithDefault("datasets/locomo10.json")
    String dataset();

    @WithDefault("0,1,2,3,4,5,6,7,8,9")
    String conversations();

    @WithDefault("100")
    int topK();

    @WithDefault("results")
    String outputDir();

    @WithDefault("false")
    boolean skipIngest();

    Optional<String> userIdFormat();

    Cognition cognition();

    HttpClient httpclient();

    LongMemEval longmemeval();

    Beam beam();

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

    interface LongMemEval {

        @WithDefault("datasets/longmemeval_s_cleaned.json")
        String dataset();

        @WithDefault("5")
        int perType();

        @WithDefault("42")
        int seed();

        Optional<String> questionTypes();
    }

    interface Beam {

        @WithDefault("datasets/beam")
        String datasetDir();

        @WithDefault("100K")
        String chatSizes();

        @WithDefault("2")
        int maxChats();
    }
}
