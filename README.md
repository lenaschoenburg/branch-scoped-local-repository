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

Add the extension to your project's `.mvn/extensions.xml`:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0">
  <extension>
    <groupId>io.github.lenaschoenburg</groupId>
    <artifactId>branch-scoped-local-repository</artifactId>
    <version>0.1.0</version>
  </extension>
</extensions>
```

and enable the split local repository, e.g. in `.mvn/maven.config`:

```
-Daether.enhancedLocalRepository.split=true
```

The extension only changes the *local* prefix (default `installed`) of the split layout. All other
split configuration (`splitRemote`, custom prefixes, …) keeps working; a custom
`-Daether.enhancedLocalRepository.localPrefix=foo` becomes the base of the branch-scoped prefix
(`foo/<branch>`).

### Configuration

| Property | Default | Effect |
|---|---|---|
| `branchScopedLocalRepo.enabled` | `true` | Set to `false` to keep the extension installed but disable branch scoping. |

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

One quirk when the consuming project already enables `split=true`: the split layout applies while
Maven bootstraps core extensions, using the *default* prefix `installed`. A locally-built SNAPSHOT
of this extension must therefore be installed into the split location, or bootstrap will not find
it:

```bash
mvn install -Daether.enhancedLocalRepository.split=true
# lands in ~/.m2/repository/installed/io/github/lenaschoenburg/...
```

To verify the extension is active, run any goal with `-X` and look for the debug log line
`Branch-scoped local repository: using local prefix 'installed/<branch>'`.

## Building

```bash
mvn verify
```

Runs unit tests plus integration tests that boot a real Sisu injector (verifying the
`@Priority`-based override of the resolver's default component) and compose paths through the real
`EnhancedLocalRepositoryManagerFactory`.

## License

[Apache-2.0](LICENSE)
