# Branch-Scoped Local Repository

A Maven **core extension** that scopes locally-installed artifacts in the local repository by
**git branch**, building on Maven Resolver's
[split local repository](https://maven.apache.org/resolver/local-repository.html) feature.

## Why

Multiple worktrees or branches of a large monorepo clobber each other's `install`ed SNAPSHOTs in
`~/.m2/repository`: whichever branch installed last wins, and builds on the other branch silently
pick up wrong artifacts. With this extension and the split local repository enabled, installs land
under a branch-specific prefix while downloaded artifacts stay shared:

```
~/.m2/repository/
├── installed/
│   ├── main/io/example/...            # `mvn install` from a checkout of main
│   └── stable/8.7/io/example/...      # `mvn install` from a checkout of stable/8.7
└── cached/io/example/...              # downloads, shared by all branches
```

## Compatibility

**Requires Maven 3.9.x** (Maven Resolver 1.9.x). Maven 4 / Resolver 2.x changed the relevant
extension point (`RepositoryKeyFunction` SPI) and is not supported. Works with mvnd 1.x (which
embeds Maven 3.9); branch switches are picked up per build, so a long-lived daemon does not need a
restart.

## Usage

### Per-developer opt-in (recommended)

Maven 3.9 has no user-level `extensions.xml` (that arrives with Maven 4), but the extension jar is
fully self-contained, so it can be loaded per user without touching any project. The install
script places the latest release into the `lib/ext/` directory of every Maven installation it
finds — `mvn` and `mvnd` on the `PATH`, and all Maven wrapper distributions:

```bash
curl -fsSL https://raw.githubusercontent.com/lenaschoenburg/branch-scoped-local-repository/main/install.sh | bash
```

Re-run it after upgrading Maven/mvnd or when the wrapper downloads a new distribution; opt out
again with `install.sh --uninstall` (`--dry-run` shows what would happen, an explicit version can
be passed as an argument).

Manual alternatives: copy the jar into `$MAVEN_HOME/lib/ext/` yourself, or pass
`-Dmaven.ext.class.path=<path-to-jar>` — for `mvn`/`mvnw` this can come from the environment
(`export MAVEN_OPTS="-Dmaven.ext.class.path=..."`); mvnd only honors it as an actual command-line
flag.

Once loaded, the extension turns on the split local repository by itself whenever it detects a git
branch, so no project files change and no further flags are needed. Builds of projects that are
not git checkouts are unaffected.

### Per-project

Alternatively, add the extension to a project's `.mvn/extensions.xml` to enable it for everyone
building that project:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0">
  <extension>
    <groupId>io.github.lenaschoenburg</groupId>
    <artifactId>branch-scoped-local-repository</artifactId>
    <version>0.2.0</version>
  </extension>
</extensions>
```

### Configuration

The extension only changes the *local* prefix (default `installed`) of the split layout and turns
`split` on when a branch is detected. All other split configuration (`splitRemote`, custom
prefixes, …) keeps working; a custom `-Daether.enhancedLocalRepository.localPrefix=foo` becomes
the base of the branch-scoped prefix (`foo/<branch>`).

| Property | Default | Effect |
|---|---|---|
| `branchScopedLocalRepo.enabled` | `true` | Set to `false` to keep the extension installed but disable branch scoping (and the implied `split`). |
| `aether.enhancedLocalRepository.split` | `true` when a branch is detected, resolver default (`false`) otherwise | Explicit values always win, so `-Daether.enhancedLocalRepository.split=false` restores the plain layout without removing the extension. |

### Branch detection

The current branch is read directly from the git metadata of the top-level project directory
(`maven.multiModuleProjectDirectory`, falling back to `user.dir`) — no `git` binary is needed, and
linked worktrees (`.git` file with `gitdir:` indirection) are supported. A detached HEAD (e.g. in
CI) is scoped as `detached-<12 hex chars of the commit>`. If no branch can be determined, the
extension gracefully degrades to the unscoped default prefix.

Branch names are sanitized into path fragments that are safe on all platforms, including Windows:
`/` is kept as a directory separator (so `stable/8.7` nests naturally), other unsafe characters
are replaced with `-`, Windows reserved device names are escaped, and overlong names are truncated
with a deterministic hash suffix.

## Trying it out from a local build

Maven resolves core extensions *before* this extension is active, so a locally-built SNAPSHOT of
this extension must be installed where that bootstrap phase looks:

- No split configuration anywhere (the usual case with the per-developer opt-in): bootstrap uses
  the plain layout, so install with branch scoping disabled:
  `mvn install -DbranchScopedLocalRepo.enabled=false`
- The consuming project sets `split=true` explicitly (e.g. in `.mvn/maven.config`): bootstrap uses
  the split layout with the default prefix, so install with
  `mvn install -Daether.enhancedLocalRepository.split=true`.

To verify the extension is active, run any goal with `-X` and look for the debug log line
`Branch-scoped local repository: split=true, using local prefix 'installed/<branch>'`.

## Building

```bash
mvn verify
```

Local builds use the dev version `0-SNAPSHOT` (the pom's `${revision}` default).

## Releasing

Create a GitHub release with a tag named `v<version>` (e.g. `v0.2.0`) — that is the whole
process. The release workflow derives the version from the tag, builds, signs, and publishes to
Maven Central; no version-bump commits exist.

Runs unit tests plus integration tests that boot a real Sisu injector (verifying the
`@Priority`-based override of the resolver's default component) and compose paths through the real
`EnhancedLocalRepositoryManagerFactory`.

## License

[Apache-2.0](LICENSE)
