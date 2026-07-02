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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the current git branch of a project by reading the repository metadata files directly,
 * without spawning a git process, and sanitizes branch names into path fragments that are safe on
 * all platforms (including Windows).
 */
final class GitBranch {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitBranch.class);

  private static final String HEAD_REF_PREFIX = "ref: refs/heads/";
  private static final String GITDIR_PREFIX = "gitdir:";
  private static final Pattern COMMIT_HASH = Pattern.compile("[0-9a-fA-F]{40,64}");
  private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
  private static final Set<String> WINDOWS_RESERVED_NAMES =
      Set.of(
          "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
          "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
  private static final int MAX_LENGTH = 80;
  private static final int HASH_SUFFIX_HEX_CHARS = 8;
  private static final int DETACHED_HASH_CHARS = 12;

  private GitBranch() {}

  /**
   * Returns the branch checked out at {@code projectRoot} (e.g. {@code main} or {@code
   * stable/8.7}), {@code detached-<12 hex chars>} for a detached HEAD, or {@code null} if the
   * branch cannot be determined.
   */
  static String detect(final Path projectRoot) {
    try {
      final Path gitDir = findGitDir(projectRoot);
      if (gitDir == null) {
        LOGGER.debug("No .git directory found at {}", projectRoot);
        return null;
      }
      final Path headFile = gitDir.resolve("HEAD");
      if (!Files.isRegularFile(headFile)) {
        LOGGER.debug("No HEAD file at {}", headFile);
        return null;
      }
      final String head = firstLine(Files.readString(headFile, StandardCharsets.UTF_8)).trim();
      if (head.startsWith(HEAD_REF_PREFIX)) {
        final String branch = head.substring(HEAD_REF_PREFIX.length()).trim();
        return branch.isEmpty() ? null : branch;
      }
      if (COMMIT_HASH.matcher(head).matches()) {
        return "detached-"
            + head.substring(0, DETACHED_HASH_CHARS).toLowerCase(Locale.ROOT);
      }
      LOGGER.debug("Unrecognized HEAD content at {}", headFile);
      return null;
    } catch (final IOException e) {
      LOGGER.debug("Failed to read git metadata at {}", projectRoot, e);
      return null;
    }
  }

  /**
   * Resolves the git directory for {@code projectRoot}: either {@code .git} itself, or — for
   * linked worktrees, where {@code .git} is a file — the directory its {@code gitdir:} line points
   * to (possibly relative to the project root).
   */
  private static Path findGitDir(final Path projectRoot) throws IOException {
    final Path dotGit = projectRoot.resolve(".git");
    if (Files.isDirectory(dotGit)) {
      return dotGit;
    }
    if (Files.isRegularFile(dotGit)) {
      for (final String line : Files.readAllLines(dotGit, StandardCharsets.UTF_8)) {
        if (line.startsWith(GITDIR_PREFIX)) {
          final String path = line.substring(GITDIR_PREFIX.length()).trim();
          if (!path.isEmpty()) {
            return projectRoot.resolve(path).normalize();
          }
        }
      }
      LOGGER.debug("No gitdir line in {}", dotGit);
    }
    return null;
  }

  /**
   * Turns a branch name into a path fragment that is safe on all platforms. Slashes are kept as
   * directory separators; within each segment, characters outside {@code [A-Za-z0-9._-]} are
   * replaced with {@code -}, trailing dots and spaces are stripped, Windows reserved device names
   * get a {@code -} appended, and empty segments are dropped. Names longer than {@value
   * #MAX_LENGTH} characters are truncated with a deterministic hash suffix. Returns {@code null}
   * if nothing usable remains.
   */
  static String sanitize(final String branch) {
    final List<String> segments = new ArrayList<>();
    for (final String segment : branch.split("/")) {
      String cleaned = UNSAFE_CHARS.matcher(segment).replaceAll("-");
      cleaned = stripTrailingDotsAndSpaces(cleaned);
      if (cleaned.isEmpty()) {
        continue;
      }
      if (WINDOWS_RESERVED_NAMES.contains(cleaned.toLowerCase(Locale.ROOT))) {
        cleaned += "-";
      }
      segments.add(cleaned);
    }
    if (segments.isEmpty()) {
      return null;
    }
    String joined = String.join("/", segments);
    if (joined.length() > MAX_LENGTH) {
      final String suffix = "-" + sha256Hex(branch).substring(0, HASH_SUFFIX_HEX_CHARS);
      String truncated = joined.substring(0, MAX_LENGTH - suffix.length());
      truncated = truncated.replaceAll("[/.]+$", "");
      joined = truncated + suffix;
    }
    return joined;
  }

  private static String stripTrailingDotsAndSpaces(final String segment) {
    int end = segment.length();
    while (end > 0 && (segment.charAt(end - 1) == '.' || segment.charAt(end - 1) == ' ')) {
      end--;
    }
    return segment.substring(0, end);
  }

  private static String firstLine(final String content) {
    final int newline = content.indexOf('\n');
    return newline < 0 ? content : content.substring(0, newline);
  }

  private static String sha256Hex(final String value) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required to be available on all JVMs", e);
    }
    final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
    final StringBuilder hex = new StringBuilder(hash.length * 2);
    for (final byte b : hash) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
