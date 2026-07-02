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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.aether.internal.impl.DefaultLocalPathPrefixComposerFactory;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposerFactory;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.inject.BeanLocator;
import org.eclipse.sisu.space.BeanScanning;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the mechanism the extension relies on inside Maven: when Sisu sees both the resolver's
 * default {@code LocalPathPrefixComposerFactory} and this extension's {@code @Priority(100)} one,
 * an unqualified injection request — like the one in {@code EnhancedLocalRepositoryManagerFactory}
 * — must yield the branch-scoped factory. Uses index-based scanning ({@code
 * META-INF/sisu/javax.inject.Named}), like Maven itself, so it also proves the sisu-maven-plugin
 * index generation works.
 */
final class SisuPriorityOverrideIT {

  private Injector injector;

  @BeforeEach
  void setUp() {
    final URLClassSpace space = new URLClassSpace(getClass().getClassLoader());
    injector = Guice.createInjector(new WireModule(new SpaceModule(space, BeanScanning.INDEX)));
  }

  @Test
  void unqualifiedInjectionYieldsBranchScopedFactory() {
    final LocalPathPrefixComposerFactory factory =
        injector.getInstance(LocalPathPrefixComposerFactory.class);
    assertInstanceOf(BranchScopedLocalPathPrefixComposerFactory.class, factory);
  }

  @Test
  void bothFactoriesAreDiscoveredAndOursRanksFirst() {
    final BeanLocator locator = injector.getInstance(BeanLocator.class);
    final List<Class<?>> implementations = new ArrayList<>();
    for (final BeanEntry<Annotation, LocalPathPrefixComposerFactory> entry :
        locator.locate(Key.get(LocalPathPrefixComposerFactory.class))) {
      implementations.add(entry.getImplementationClass());
    }
    assertTrue(
        implementations.contains(DefaultLocalPathPrefixComposerFactory.class),
        "the resolver's default factory must be visible for this test to be meaningful, saw: "
            + implementations);
    assertEquals(BranchScopedLocalPathPrefixComposerFactory.class, implementations.get(0));
  }
}
