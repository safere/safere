// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Compiles one pattern in a fresh JVM for compiler initialization tests. */
public final class CompileInitializationProbe {
  private CompileInitializationProbe() {}

  /** Compiles the Base64-encoded UTF-8 pattern supplied as the first command-line argument. */
  public static void main(String[] args) throws Exception {
    String regex = new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8);
    if (args.length > 1 && args[1].equals("concurrent")) {
      compileAndSearchConcurrently(regex);
    } else {
      Pattern.compile(regex);
    }
  }

  private static void compileAndSearchConcurrently(String regex) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Utf8Input input = Utf8Input.validated("aé".getBytes(StandardCharsets.UTF_8));
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(
            executor.submit(() -> {
              start.await();
              Pattern pattern = Pattern.compile(regex);
              Matcher matcher = pattern.matcher("aé");
              Utf8Matcher utf8Matcher = pattern.matcher(input);
              return matcher.find()
                  && matcher.start(1) == 0
                  && matcher.end(1) == 1
                  && utf8Matcher.find()
                  && utf8Matcher.start(1) == 0
                  && utf8Matcher.end(1) == 1;
            }));
      }
      start.countDown();
      for (Future<Boolean> result : results) {
        if (!result.get()) {
          throw new AssertionError("concurrent first search changed capture participation");
        }
      }
    }
  }
}
