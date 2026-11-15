// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/** Prepared execution state for one supported cross-engine benchmark trial. */
final class CrossEngineTrialRunner implements AutoCloseable {

  private final List<RegexEngineVariant.CompiledRegex> patterns;
  private final BenchmarkOperation.BenchmarkTask task;
  private final ColdResult coldResult;

  private CrossEngineTrialRunner(
      List<RegexEngineVariant.CompiledRegex> patterns,
      BenchmarkOperation.BenchmarkTask task,
      ColdResult coldResult) {
    this.patterns = patterns;
    this.task = task;
    this.coldResult = coldResult;
  }

  static CrossEngineTrialRunner prepare(
      String trialId, CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    BenchmarkData data = BenchmarkData.get();
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
    return prepare(data, trial, expectedTimingGroup, true);
  }

  static CrossEngineTrialRunner prepareColdStart(
      String trialId, CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    BenchmarkData data = BenchmarkData.get();
    CrossEngineBenchmarkPlan.Trial trial = CrossEngineBenchmarkPlan.load().resolve(trialId);
    return prepareColdStart(data, trial, expectedTimingGroup);
  }

  static CrossEngineTrialRunner prepareColdStart(
      BenchmarkData data,
      CrossEngineBenchmarkPlan.Trial trial,
      CrossEngineWorkload.TimingGroup expectedTimingGroup) {
    if (trial.workload().measurement().mode()
        != DeclarativeBenchmarkPlan.MeasurementMode.SINGLE_SHOT_COLD_START) {
      throw new IllegalArgumentException(trial.id() + " requires singleShotColdStart mode");
    }
    if (!trial.workload().operation().supportsColdStart()) {
      throw new IllegalArgumentException(
          trial.id() + " operation does not support cold-start execution");
    }
    return prepare(data, trial, expectedTimingGroup, false);
  }

  private static CrossEngineTrialRunner prepare(
      BenchmarkData data,
      CrossEngineBenchmarkPlan.Trial trial,
      CrossEngineWorkload.TimingGroup expectedTimingGroup,
      boolean validateBeforeMeasurement) {
    trial.variant().validateScanProvider();
    String trialId = trial.id();
    CrossEngineWorkload workload = trial.workload();
    if (workload.timingGroup() != expectedTimingGroup) {
      throw new IllegalArgumentException(
          trialId + " belongs to " + workload.timingGroup() + ", not " + expectedTimingGroup);
    }

    List<RegexEngineVariant.RegexInput> inputs =
        trial.variant().prepareInputs(data, workload.inputKeys());
    RegexEngineVariant.PreparedReplacement replacement =
        trial.variant().prepareReplacement(workload.replacement());
    List<String> patternSources = workload.patterns();
    List<RegexEngineVariant.CompiledRegex> patterns = new ArrayList<>();
    try {
      // A spare compilation would warm process-wide initialization before a cold combined trial.
      if (!workload.operation().compilesDuringMeasurement()) {
        for (String pattern : patternSources) {
          patterns.add(trial.variant().compile(pattern));
        }
      }
      if (validateBeforeMeasurement) {
        Object actual =
            workload
                .operation()
                .execute(
                    trial.variant(),
                    patternSources,
                    patterns,
                    inputs,
                    workload.groups(),
                    replacement,
                    workload.limit(),
                    workload.lifecycle(),
                    workload.flagSet(),
                    workload.seed(),
                    workload.count(),
                    workload.mode());
        trial.validate(actual);
      }
      ColdResult coldResult =
          !validateBeforeMeasurement && workload.operation() != BenchmarkOperation.COMPILE
              ? new ColdResult(trial)
              : null;
      BenchmarkOperation.BenchmarkTask task;
      if (coldResult != null) {
        int[] groups = workload.groups();
        task =
            blackhole -> {
              Object actual =
                  workload
                      .operation()
                      .execute(
                          trial.variant(),
                          patternSources,
                          patterns,
                          inputs,
                          groups,
                          replacement,
                          workload.limit(),
                          workload.lifecycle(),
                          workload.flagSet(),
                          workload.seed(),
                          workload.count(),
                          workload.mode());
              coldResult.actual = actual;
              coldResult.completed = true;
              blackhole.consume(actual);
            };
      } else {
        task =
            workload
                .operation()
                .bind(
                    trial.variant(),
                    patternSources,
                    patterns,
                    inputs,
                    workload.groups(),
                    replacement,
                    workload.limit(),
                    workload.lifecycle(),
                    workload.flagSet(),
                    workload.seed(),
                    workload.count(),
                    workload.mode());
      }
      return new CrossEngineTrialRunner(List.copyOf(patterns), task, coldResult);
    } catch (RuntimeException | Error exception) {
      close(patterns);
      throw exception;
    }
  }

  void run(Blackhole blackhole) {
    task.run(blackhole);
  }

  void validateMeasuredResult() {
    if (coldResult != null && coldResult.completed) {
      coldResult.trial.validate(coldResult.actual);
    }
  }

  private static final class ColdResult {
    final CrossEngineBenchmarkPlan.Trial trial;
    Object actual;
    boolean completed;

    ColdResult(CrossEngineBenchmarkPlan.Trial trial) {
      this.trial = trial;
    }
  }

  @Override
  public void close() {
    close(patterns);
  }

  private static void close(List<RegexEngineVariant.CompiledRegex> patterns) {
    for (RegexEngineVariant.CompiledRegex pattern : patterns.reversed()) {
      pattern.close();
    }
  }
}
