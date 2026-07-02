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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposer;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BranchScopedLocalPathPrefixComposerFactoryTest {

  private static final Artifact RELEASE_ARTIFACT = new DefaultArtifact("com.example:demo:1.0.0");
  private static final Artifact SNAPSHOT_ARTIFACT =
      new DefaultArtifact("com.example:demo:1.0.0-SNAPSHOT");
  private static final RemoteRepository CENTRAL =
      new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
          .build();

  private final BranchScopedLocalPathPrefixComposerFactory factory =
      new BranchScopedLocalPathPrefixComposerFactory();

  @TempDir Path tempDir;
  private DefaultRepositorySystemSession session;

  @BeforeEach
  @SuppressWarnings("deprecation")
  void setUp() {
    session = new DefaultRepositorySystemSession();
    session.setConfigProperty("aether.enhancedLocalRepository.split", "true");
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_PROJECT_DIRECTORY,
        tempDir.toString());
  }

  private void checkoutBranch(final String branch) throws IOException {
    final Path gitDir = Files.createDirectories(tempDir.resolve(".git"));
    Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/" + branch + "\n");
  }

  private LocalPathPrefixComposer composer() {
    return factory.createComposer(session);
  }

  @Test
  void scopesLocalPrefixByBranch() throws IOException {
    checkoutBranch("main");
    assertEquals("installed/main", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void keepsSlashesInBranchNames() throws IOException {
    checkoutBranch("lena/foo-123");
    assertEquals(
        "installed/lena/foo-123", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void remotePrefixIsNotScoped() throws IOException {
    checkoutBranch("main");
    assertEquals(
        "cached", composer().getPathPrefixForRemoteArtifact(RELEASE_ARTIFACT, CENTRAL));
  }

  @Test
  void respectsSplitLocalReleasesAndSnapshots() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.splitLocal", "true");
    assertEquals(
        "installed/main/releases", composer().getPathPrefixForLocalArtifact(RELEASE_ARTIFACT));
    assertEquals(
        "installed/main/snapshots", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void respectsSplitRemote() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.splitRemote", "true");
    assertEquals(
        "cached/releases", composer().getPathPrefixForRemoteArtifact(RELEASE_ARTIFACT, CENTRAL));
  }

  @Test
  void respectsCustomLocalPrefix() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.localPrefix", "foo");
    assertEquals("foo/main", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void unscopedWhenDisabledViaConfig() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_ENABLED, "false");
    assertEquals("installed", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void unscopedWithoutProjectDirectory() {
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_PROJECT_DIRECTORY, null);
    assertEquals("installed", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void unscopedWhenProjectDirectoryDoesNotExist() {
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_PROJECT_DIRECTORY,
        tempDir.resolve("does-not-exist").toString());
    assertEquals("installed", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void unscopedWithoutGitRepository() {
    assertEquals("installed", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void unscopedWhenHeadIsUnreadable() throws IOException {
    Files.createDirectories(tempDir.resolve(".git"));
    assertEquals("installed", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void fallsBackToUserDir() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_PROJECT_DIRECTORY, null);
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_USER_DIR, tempDir.toString());
    assertEquals("installed/main", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void sanitizesBranchForWindowsSafety() throws IOException {
    checkoutBranch("bugfix/weird name?");
    assertEquals(
        "installed/bugfix/weird-name-",
        composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void detachedHeadUsesCommitHash() throws IOException {
    final Path gitDir = Files.createDirectories(tempDir.resolve(".git"));
    Files.writeString(gitDir.resolve("HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
    assertEquals(
        "installed/detached-0123456789ab",
        composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void noPrefixesWhenSplitIsExplicitlyOff() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.split", "false");
    assertNull(composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }

  @Test
  void detectedBranchTurnsSplitOnByDefault() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.split", null);
    assertEquals("installed/main", composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
    assertEquals("cached", composer().getPathPrefixForRemoteArtifact(RELEASE_ARTIFACT, CENTRAL));
  }

  @Test
  void splitStaysOffWithoutDetectedBranch() {
    session.setConfigProperty("aether.enhancedLocalRepository.split", null);
    assertNull(composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
    assertNull(composer().getPathPrefixForRemoteArtifact(RELEASE_ARTIFACT, CENTRAL));
  }

  @Test
  void splitStaysOffWhenDisabledViaConfigWithoutExplicitSplit() throws IOException {
    checkoutBranch("main");
    session.setConfigProperty("aether.enhancedLocalRepository.split", null);
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_ENABLED, "false");
    assertNull(composer().getPathPrefixForLocalArtifact(SNAPSHOT_ARTIFACT));
  }
}
