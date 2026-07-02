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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposer;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposerFactorySupport;
import org.eclipse.aether.util.ConfigUtils;
import org.eclipse.sisu.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.eclipse.aether.internal.impl.LocalPathPrefixComposerFactory} that additionally
 * scopes the local (install) prefix of the split local repository by the current git branch:
 * installed artifacts land under {@code installed/<branch>/...} while downloaded artifacts stay
 * shared under {@code cached/...}.
 *
 * <p>The higher Sisu {@link Priority} makes this component win over the resolver's default factory
 * when Maven injects the unqualified {@code LocalPathPrefixComposerFactory} into the enhanced
 * local repository manager factory.
 */
@Singleton
@Named("branch-scoped")
@Priority(100)
public final class BranchScopedLocalPathPrefixComposerFactory
    extends LocalPathPrefixComposerFactorySupport {

  /** Session config property to disable branch scoping without removing the extension. */
  static final String CONFIG_PROP_ENABLED = "branchScopedLocalRepo.enabled";

  static final String CONFIG_PROP_PROJECT_DIRECTORY = "maven.multiModuleProjectDirectory";
  static final String CONFIG_PROP_USER_DIR = "user.dir";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(BranchScopedLocalPathPrefixComposerFactory.class);

  @Override
  public LocalPathPrefixComposer createComposer(final RepositorySystemSession session) {
    final String scopedPrefix = branchScopedLocalPrefix(session);
    final boolean split;
    final String localPrefix;
    if (scopedPrefix != null) {
      // A detected branch turns the split local repository on, so registering the extension
      // (e.g. user-wide in ~/.m2/extensions.xml) is the only opt-in step needed. An explicit
      // aether.enhancedLocalRepository.split setting still wins.
      split = ConfigUtils.getBoolean(session, true, CONF_PROP_SPLIT);
      localPrefix = scopedPrefix;
    } else {
      split = isSplit(session);
      localPrefix = getLocalPrefix(session);
    }
    LOGGER.debug(
        "Branch-scoped local repository: split={}, using local prefix '{}'", split, localPrefix);
    return new BranchScopedComposer(
        split,
        localPrefix,
        isSplitLocal(session),
        getRemotePrefix(session),
        isSplitRemote(session),
        isSplitRemoteRepository(session),
        isSplitRemoteRepositoryLast(session),
        getReleasesPrefix(session),
        getSnapshotsPrefix(session));
  }

  /** Returns the branch-scoped local prefix, or {@code null} when no scoping applies. */
  private String branchScopedLocalPrefix(final RepositorySystemSession session) {
    if (!ConfigUtils.getBoolean(session, true, CONFIG_PROP_ENABLED)) {
      LOGGER.debug("Branch scoping disabled via {}", CONFIG_PROP_ENABLED);
      return null;
    }
    final Path projectRoot = projectRoot(session);
    if (projectRoot == null) {
      LOGGER.debug("No project directory found in session, not scoping by branch");
      return null;
    }
    final String branch = GitBranch.detect(projectRoot);
    if (branch == null) {
      LOGGER.debug("No git branch detected at {}, not scoping by branch", projectRoot);
      return null;
    }
    final String sanitized = GitBranch.sanitize(branch);
    if (sanitized == null) {
      LOGGER.debug("Branch name '{}' left nothing usable after sanitization", branch);
      return null;
    }
    return getLocalPrefix(session) + "/" + sanitized;
  }

  private static Path projectRoot(final RepositorySystemSession session) {
    final String directory =
        ConfigUtils.getString(
            session, null, CONFIG_PROP_PROJECT_DIRECTORY, CONFIG_PROP_USER_DIR);
    if (directory == null) {
      return null;
    }
    final Path path = Paths.get(directory);
    return Files.isDirectory(path) ? path : null;
  }

  /**
   * All path composition is inherited from {@link LocalPathPrefixComposerSupport}; only the {@code
   * localPrefix} passed in differs from the default composer. Immutable, as required of composers.
   */
  private static final class BranchScopedComposer extends LocalPathPrefixComposerSupport {

    @SuppressWarnings("checkstyle:parameternumber")
    private BranchScopedComposer(
        final boolean split,
        final String localPrefix,
        final boolean splitLocal,
        final String remotePrefix,
        final boolean splitRemote,
        final boolean splitRemoteRepository,
        final boolean splitRemoteRepositoryLast,
        final String releasesPrefix,
        final String snapshotsPrefix) {
      super(
          split,
          localPrefix,
          splitLocal,
          remotePrefix,
          splitRemote,
          splitRemoteRepository,
          splitRemoteRepositoryLast,
          releasesPrefix,
          snapshotsPrefix);
    }
  }
}
