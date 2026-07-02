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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.internal.impl.DefaultLocalPathComposer;
import org.eclipse.aether.internal.impl.DefaultTrackingFileManager;
import org.eclipse.aether.internal.impl.EnhancedLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end check through the real {@link EnhancedLocalRepositoryManagerFactory}: with the
 * branch-scoped composer factory plugged into the same injection point Maven uses, installed
 * artifacts resolve to {@code installed/<branch>/...} while remote artifacts stay under {@code
 * cached/...}.
 */
final class EnhancedLocalRepositoryManagerIT {

  private static final RemoteRepository CENTRAL =
      new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
          .build();

  @TempDir Path tempDir;

  private DefaultRepositorySystemSession session;
  private LocalRepositoryManager manager;

  @BeforeEach
  @SuppressWarnings("deprecation")
  void setUp() throws IOException, NoLocalRepositoryManagerException {
    final Path project = Files.createDirectories(tempDir.resolve("project"));
    final Path gitDir = Files.createDirectories(project.resolve(".git"));
    Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/stable/8.7\n");

    session = new DefaultRepositorySystemSession();
    session.setConfigProperty("aether.enhancedLocalRepository.split", "true");
    session.setConfigProperty("aether.enhancedLocalRepository.splitRemote", "true");
    session.setConfigProperty(
        BranchScopedLocalPathPrefixComposerFactory.CONFIG_PROP_PROJECT_DIRECTORY,
        project.toString());

    final EnhancedLocalRepositoryManagerFactory factory =
        new EnhancedLocalRepositoryManagerFactory(
            new DefaultLocalPathComposer(),
            new DefaultTrackingFileManager(),
            new BranchScopedLocalPathPrefixComposerFactory());
    manager =
        factory.newInstance(session, new LocalRepository(tempDir.resolve("repo").toFile()));
  }

  @Test
  void installedArtifactsAreScopedByBranch() {
    assertEquals(
        "installed/stable/8.7/com/example/demo/1.0.0-SNAPSHOT/demo-1.0.0-SNAPSHOT.jar",
        manager.getPathForLocalArtifact(
            new DefaultArtifact("com.example:demo:1.0.0-SNAPSHOT")));
  }

  @Test
  void remoteArtifactsAreSharedAcrossBranches() {
    assertEquals(
        "cached/releases/com/example/demo/1.0.0/demo-1.0.0.jar",
        manager.getPathForRemoteArtifact(
            new DefaultArtifact("com.example:demo:1.0.0"), CENTRAL, null));
  }
}
