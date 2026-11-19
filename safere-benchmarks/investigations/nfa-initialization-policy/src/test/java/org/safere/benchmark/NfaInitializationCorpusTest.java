// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.infra.Blackhole;

class NfaInitializationCorpusTest {
  @TempDir Path output;

  @Test
  void declaredFirstOperationsObserveTheSameCapturesAndReplacementAsTheJdk() throws Exception {
    Path corpus = Path.of("investigations/nfa-initialization-policy");
    if (!Files.exists(corpus)) {
      corpus = Path.of("safere-benchmarks").resolve(corpus);
    }
    BenchmarkInputMaterializer.main(new String[] {corpus.toString(), output.toString()});
    JsonObject manifest =
        JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
    Blackhole blackhole =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is"
                + " dangerous.");
    Set<String> observed = new HashSet<>();
    for (JsonElement element :
        manifest.getAsJsonObject("executionPlan").getAsJsonArray("entries")) {
      JsonObject entry = element.getAsJsonObject();
      if (!entry.get("status").getAsString().equals("runnable")
          || !List.of("safere-string", "safere-utf8")
              .contains(entry.get("engineId").getAsString())) {
        continue;
      }
      String regex = entry.getAsJsonArray("patterns").get(0).getAsString();
      String operation = entry.get("operation").getAsString();
      if (operation.equals("compile")) {
        continue;
      }
      String key = entry.getAsJsonArray("inputs").get(0).getAsString();
      String file =
          manifest.getAsJsonObject("inputs").getAsJsonObject(key).get("file").getAsString();
      String text = Files.readString(output.resolve(file));
      java.util.regex.Matcher oracle = java.util.regex.Pattern.compile(regex).matcher(text);
      Object expected =
          switch (operation) {
            case "compileAndFind" -> oracle.find();
            case "compileAndMatches", "matches" -> oracle.matches();
            case "compileAndFindGroup" -> oracle.find() ? oracle.group(1) : null;
            case "compileAndReplaceAll", "replaceAll" -> oracle.replaceAll("<$1>");
            default -> throw new AssertionError(operation);
          };
      BenchmarkOperation op =
          BenchmarkOperation.fromDeclarative(
              DeclarativeBenchmarkPlan.Operation.valueOf(
                  operation
                      .replaceAll("([a-z])([A-Z])", "$1_$2")
                      .toUpperCase(java.util.Locale.ROOT)));
      RegexEngineVariant variant = RegexEngineVariant.fromId(entry.get("engineId").getAsString());
      observed.add(operation + "@" + variant.id());
      List<RegexEngineVariant.CompiledRegex> patterns =
          op.compilesDuringMeasurement() ? List.of() : List.of(variant.compile(regex));
      try {
        var inputs =
            List.<RegexEngineVariant.RegexInput>of(
                variant == RegexEngineVariant.SAFERE_STRING
                    ? new RegexEngineVariant.StringRegexInput(text)
                    : RegexEngineVariant.utf8Input(
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var replacement = variant.prepareReplacement("<$1>");
        Object actual =
            op.execute(
                variant,
                List.of(regex),
                patterns,
                inputs,
                new int[] {1},
                replacement,
                0,
                DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
                "",
                0,
                1,
                "");
        assertThat(actual).as(entry.get("id").getAsString()).isEqualTo(expected);
        // Binding with no precompiled pattern protects the cold-start timing boundary.
        op.bind(
                variant,
                List.of(regex),
                patterns,
                inputs,
                new int[] {1},
                replacement,
                0,
                DeclarativeBenchmarkPlan.MatcherLifecycle.NONE,
                "",
                0,
                1,
                "")
            .run(blackhole);
      } finally {
        for (var pattern : patterns) {
          pattern.close();
        }
      }
    }
    assertThat(observed)
        .contains(
            "compileAndFindGroup@safere-string",
            "compileAndReplaceAll@safere-string",
            "compileAndReplaceAll@safere-utf8",
            "compileAndMatches@safere-string",
            "compileAndFind@safere-string");
  }
}
