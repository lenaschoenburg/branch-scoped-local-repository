/*
 * Copyright Lena Schönburg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.lenaschoenburg.branchscopedrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class GitBranchTest {

  @TempDir Path tempDir;

  private Path projectWithHead(final String headContent) throws IOException {
    final Path project = Files.createDirectories(tempDir.resolve("project"));
    final Path gitDir = Files.createDirectories(project.resolve(".git"));
    Files.writeString(gitDir.resolve("HEAD"), headContent);
    return project;
  }

  @Nested
  final class Detect {

    @Test
    void normalCheckout() throws IOException {
      final Path project = projectWithHead("ref: refs/heads/main\n");
      assertEquals("main", GitBranch.detect(project));
    }

    @Test
    void branchWithSlashes() throws IOException {
      final Path project = projectWithHead("ref: refs/heads/lena/foo-123\n");
      assertEquals("lena/foo-123", GitBranch.detect(project));
    }

    @Test
    void detachedHead() throws IOException {
      final Path project = projectWithHead("F00Dc3a1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7\n");
      assertEquals("detached-f00dc3a1d2e3", GitBranch.detect(project));
    }

    @Test
    void linkedWorktreeWithRelativeGitdir() throws IOException {
      final Path mainRepo = Files.createDirectories(tempDir.resolve("main-repo"));
      final Path worktreeGitDir =
          Files.createDirectories(mainRepo.resolve(".git/worktrees/my-worktree"));
      Files.writeString(worktreeGitDir.resolve("HEAD"), "ref: refs/heads/feature/xyz\n");

      final Path worktree = Files.createDirectories(tempDir.resolve("my-worktree"));
      Files.writeString(
          worktree.resolve(".git"), "gitdir: ../main-repo/.git/worktrees/my-worktree\n");

      assertEquals("feature/xyz", GitBranch.detect(worktree));
    }

    @Test
    void linkedWorktreeWithAbsoluteGitdir() throws IOException {
      final Path mainRepo = Files.createDirectories(tempDir.resolve("main-repo"));
      final Path worktreeGitDir =
          Files.createDirectories(mainRepo.resolve(".git/worktrees/my-worktree"));
      Files.writeString(worktreeGitDir.resolve("HEAD"), "ref: refs/heads/feature/abs\n");

      final Path worktree = Files.createDirectories(tempDir.resolve("my-worktree"));
      Files.writeString(worktree.resolve(".git"), "gitdir: " + worktreeGitDir + "\n");

      assertEquals("feature/abs", GitBranch.detect(worktree));
    }

    @Test
    void noGitDirectory() {
      assertNull(GitBranch.detect(tempDir));
    }

    @Test
    void missingHeadFile() throws IOException {
      final Path project = Files.createDirectories(tempDir.resolve("project"));
      Files.createDirectories(project.resolve(".git"));
      assertNull(GitBranch.detect(project));
    }

    @Test
    void unrecognizedHeadContent() throws IOException {
      final Path project = projectWithHead("this is not a ref\n");
      assertNull(GitBranch.detect(project));
    }

    @Test
    void gitFileWithoutGitdirLine() throws IOException {
      final Path project = Files.createDirectories(tempDir.resolve("project"));
      Files.writeString(project.resolve(".git"), "nonsense\n");
      assertNull(GitBranch.detect(project));
    }
  }

  @Nested
  final class Sanitize {

    @ParameterizedTest
    @CsvSource({
      "main, main",
      "lena/foo-123, lena/foo-123",
      "stable/8.7, stable/8.7",
      "'feature/hello world!', feature/hello-world-",
      "'release/v1.', release/v1",
      "nul, nul-",
      "COM3/x, COM3-/x",
      "feature/lpt9, feature/lpt9-",
      "a//b, a/b",
    })
    void sanitizesBranchNames(final String branch, final String expected) {
      assertEquals(expected, GitBranch.sanitize(branch));
    }

    @Test
    void onlyEmptySegmentsLeftReturnsNull() {
      assertNull(GitBranch.sanitize("///"));
      assertNull(GitBranch.sanitize("..."));
    }

    @Test
    void longBranchIsTruncatedWithDeterministicHashSuffix() {
      final String branch = "feature/" + "a".repeat(120);
      final String sanitized = GitBranch.sanitize(branch);
      assertEquals(80, sanitized.length());
      assertTrue(sanitized.startsWith("feature/aaaa"));
      assertTrue(sanitized.matches(".*-[0-9a-f]{8}$"), "expected hash suffix: " + sanitized);
      assertEquals(sanitized, GitBranch.sanitize(branch));
    }

    @Test
    void differentLongBranchesGetDifferentSuffixes() {
      final String prefix = "feature/" + "a".repeat(120);
      assertTrue(
          !GitBranch.sanitize(prefix + "x").equals(GitBranch.sanitize(prefix + "y")),
          "hash suffix must distinguish branches with identical truncated prefixes");
    }
  }
}
