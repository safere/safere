// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.benchmark;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.safere.Matcher;
import org.safere.Pattern;

class NfaSpikeUnicodeCorpusTest {
  @TempDir Path output;

  @Test
  void declaredCaptureFamiliesConsumeTheExpectedTextAndExcludeOtherEngines() throws Exception {
    Path corpus = Path.of("investigations/nfa-state-spike-unicode");
    if (!Files.exists(corpus)) {
      corpus = Path.of("safere-benchmarks").resolve(corpus);
    }
    BenchmarkInputMaterializer.main(new String[] {corpus.toString(), output.toString()});
    JsonObject manifest =
        JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
    boolean sawCaptureCase = false;
    for (JsonElement element :
        manifest.getAsJsonObject("executionPlan").getAsJsonArray("entries")) {
      JsonObject entry = element.getAsJsonObject();
      if (!entry.get("workloadId").getAsString().startsWith("NfaStateSpikeUnicodeBenchmark.")) {
        continue;
      }
      String engine = entry.get("engineId").getAsString();
      if (!engine.startsWith("safere-")) {
        assertThat(entry.get("status").getAsString()).isNotEqualTo("runnable");
        continue;
      }
      if (!engine.equals("safere-string")
          || !entry.get("operation").getAsString().equals("matchesGroupLengthSum")) {
        continue;
      }
      sawCaptureCase = true;
      String inputId = entry.getAsJsonArray("inputs").get(0).getAsString();
      String file =
          manifest.getAsJsonObject("inputs").getAsJsonObject(inputId).get("file").getAsString();
      String input = Files.readString(output.resolve(file), UTF_8);
      assertThat(input.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isFalse();
      String regex = entry.getAsJsonArray("patterns").get(0).getAsString();
      JsonObject configuration =
          manifest.getAsJsonObject("benchmarkData").getAsJsonObject("configuration");
      String family = entry.get("workloadId").getAsString().split("\\.")[1];
      String expectedText =
          configuration
              .getAsJsonObject("investigation")
              .getAsJsonObject("groupText")
              .get(family)
              .getAsString();
      Matcher matcher = Pattern.compile(regex).matcher(input);
      assertThat(matcher.matches()).as(entry.get("id").getAsString()).isTrue();
      int sum = 0;
      for (int group = 1; group <= matcher.groupCount(); group++) {
        assertThat(matcher.group(group)).isEqualTo(expectedText);
        sum += matcher.group(group).length();
      }
      assertThat(sum).isEqualTo(entry.getAsJsonObject("expected").get("value").getAsInt());
      if (input.length() <= 128) {
        java.util.regex.Matcher oracle = java.util.regex.Pattern.compile(regex).matcher(input);
        assertThat(oracle.matches()).isTrue();
        for (int group = 0; group <= matcher.groupCount(); group++) {
          assertThat(matcher.start(group)).isEqualTo(oracle.start(group));
          assertThat(matcher.end(group)).isEqualTo(oracle.end(group));
        }
      }
    }
    assertThat(sawCaptureCase).isTrue();
  }
}
