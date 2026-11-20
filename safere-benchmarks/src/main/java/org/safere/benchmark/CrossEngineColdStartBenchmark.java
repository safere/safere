// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/** Generic single-shot entry point for fresh-process cold-start declarations. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class CrossEngineColdStartBenchmark {

  /** Planned workload and execution-variant ID supplied by {@code run-java-benchmarks.sh}. */
  @Param({})
  public String crossEngineColdStartTrial;

  private CrossEngineTrialRunner runner;
  private final AtomicBoolean measured = new AtomicBoolean();

  /** Prepares inputs and any untimed compilation without exercising the measured operation. */
  @Setup
  public void setup() {
    measured.set(false);
    runner =
        CrossEngineTrialRunner.prepareColdStart(
            crossEngineColdStartTrial, CrossEngineWorkload.TimingGroup.MILLISECONDS);
  }

  /** Validates the measured result and releases retained resources. */
  @TearDown
  public void tearDown() {
    try {
      runner.validateMeasuredResult();
    } finally {
      runner.close();
    }
  }

  /** Executes one declared cold-start operation. */
  @Benchmark
  public void run(Blackhole blackhole) {
    if (measured.getAndSet(true)) {
      throw new IllegalStateException("Cold-start trials require one invocation per fresh fork");
    }
    runner.run(blackhole);
  }
}
