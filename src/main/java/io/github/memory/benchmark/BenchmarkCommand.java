package io.github.memory.benchmark;

import io.github.memory.benchmark.beam.BeamBenchmark;
import io.github.memory.benchmark.locomo.LoCoMoBenchmark;
import io.github.memory.benchmark.longmemeval.LongMemEvalBenchmark;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(
        name = "benchmark",
        description = "Memory Service Benchmarks",
        subcommands = {LoCoMoBenchmark.class, LongMemEvalBenchmark.class, BeamBenchmark.class}
)
public class BenchmarkCommand {
}
