/*
 * Copyright 2018 Google LLC.
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

package com.google.cloud.tools.opensource.dependencies;

import static com.google.cloud.tools.opensource.dependencies.RepositoryUtility.CENTRAL;
import static com.google.cloud.tools.opensource.dependencies.RepositoryUtility.mavenRepositoryFromUrl;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;

/**
 * This class builds dependency graphs for Maven artifacts.
 *
 * <p>A Maven dependency graph is the tree you see in {@code mvn dependency:tree} output. This graph
 * has the following attributes:
 *
 * <ul>
 *   <li>It contains at most one node with the same group ID and artifact ID. (<a
 *       href="https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Transitive_Dependencies">dependency
 *       mediation</a>)
 *   <li>The scope of a dependency affects the scope of its children's dependencies as per <a
 *       href="https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope">Maven:
 *       Dependency Scope</a>
 *   <li>It does not contain provided-scope dependencies of transitive dependencies.
 *   <li>It does not contain optional dependencies of transitive dependencies.
 * </ul>
 *
 * <p>A full dependency graph is a dependency tree where each node's dependencies are resolved
 * recursively. This graph has the following attributes:
 *
 * <ul>
 *   <li>The same artifact, which has the same group:artifact:version, appears in different nodes in
 *       the graph.
 *   <li>The scope of a dependency does not affect the scope of its children's dependencies.
 *   <li>Provided-scope and optional dependencies are not treated differently than any other
 *       dependency.
 * </ul>
 */
public final class DependencyGraphBuilder {

  private static final RepositorySystem system = RepositoryUtility.newRepositorySystem();

  /** Maven repositories to use when resolving dependencies. */
  private final ImmutableList<RemoteRepository> repositories;
  private Path localRepository;

  static {
    OsProperties.detectOsProperties().forEach(System::setProperty);
  }

  public DependencyGraphBuilder() {
    this(ImmutableList.of(CENTRAL.getUrl()));
  }

  /**
   * @param mavenRepositoryUrls remote Maven repositories to search for dependencies
   * @throws IllegalArgumentException if a URL is malformed or does not have an allowed scheme
   */
  public DependencyGraphBuilder(Iterable<String> mavenRepositoryUrls) {
    ImmutableList.Builder<RemoteRepository> repositoryListBuilder = ImmutableList.builder();
    for (String mavenRepositoryUrl : mavenRepositoryUrls) {
      RemoteRepository repository = mavenRepositoryFromUrl(mavenRepositoryUrl);
      repositoryListBuilder.add(repository);
    }
    this.repositories = repositoryListBuilder.build();
  }
  
  /**
   * Enable temporary repositories for tests.
   */
  @VisibleForTesting
  void setLocalRepository(Path localRepository) {
    this.localRepository = localRepository;
  }
  
  private DependencyNode resolveCompileTimeDependencies(
      List<DependencyNode> dependencyNodes, boolean fullDependencies)
      throws DependencyResolutionException {

    ImmutableList.Builder<Dependency> dependenciesBuilder = ImmutableList.builder();
    for (DependencyNode dependencyNode : dependencyNodes) {
      Dependency dependency = dependencyNode.getDependency();
      if (dependency == null) {
        // Root DependencyNode has null dependency field.
        dependenciesBuilder.add(new Dependency(dependencyNode.getArtifact(), "compile"));
      } else {
        // The dependency field carries exclusions
        dependenciesBuilder.add(dependency.setScope("compile"));
      }
    }
    ImmutableList<Dependency> dependencyList = dependenciesBuilder.build();

    DefaultRepositorySystemSession session =
        fullDependencies
            ? RepositoryUtility.newSessionForFullDependency(system)
            : RepositoryUtility.newSession(system);
            
    if (localRepository != null) {
      LocalRepository local = new LocalRepository(localRepository.toAbsolutePath().toString());
      session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local));
    }

    CollectRequest collectRequest = new CollectRequest();
    if (dependencyList.size() == 1) {
      // With setRoot, the result includes dependencies with `optional:true` or `provided`
      collectRequest.setRoot(dependencyList.get(0));
    } else {
      collectRequest.setDependencies(dependencyList);
    }
    for (RemoteRepository repository : repositories) {
      collectRequest.addRepository(repository);
    }
    DependencyRequest dependencyRequest = new DependencyRequest();
    dependencyRequest.setCollectRequest(collectRequest);

    // resolveDependencies equals to calling both collectDependencies (build dependency tree) and
    // resolveArtifacts (download JAR files).
    DependencyResult dependencyResult = system.resolveDependencies(session, dependencyRequest);
    return dependencyResult.getRoot();
  }

  /**
   * Finds the full compile time, transitive dependency graph including duplicates, conflicting
   * versions, and provided and optional dependencies. In the event of I/O errors, missing
   * artifacts, and other problems, it can return an incomplete graph.
   *
   * @param artifacts Maven artifacts to retrieve their dependencies
   * @return dependency graph representing the tree of Maven artifacts
   */
  public DependencyGraph buildFullDependencyGraph(List<Artifact> artifacts) {
    ImmutableList<DependencyNode> dependencyNodes =
        artifacts.stream().map(DefaultDependencyNode::new).collect(toImmutableList());
    return buildDependencyGraph(dependencyNodes, GraphTraversalOption.FULL);
  }

  /**
   * Builds the transitive dependency graph as seen by Maven. It does not include duplicates and
   * conflicting versions. That is, this resolves conflicting versions by picking the first version
   * seen. This is how Maven normally operates.
   * 
   * In the event of I/O errors, missing artifacts, and other problems, it can
   * return an incomplete graph.
   */
  public DependencyGraph buildMavenDependencyGraph(Dependency dependency) {
    return buildDependencyGraph(
        ImmutableList.of(new DefaultDependencyNode(dependency)), GraphTraversalOption.MAVEN);
  }

  private DependencyGraph buildDependencyGraph(
      List<DependencyNode> dependencyNodes, GraphTraversalOption traversalOption) {
    boolean fullDependency = traversalOption == GraphTraversalOption.FULL;
    
    try {
      DependencyNode node = resolveCompileTimeDependencies(dependencyNodes, fullDependency);
      return DependencyGraph.from(node);
    } catch (DependencyResolutionException ex) {
      DependencyResult result = ex.getResult();
      DependencyGraph graph = DependencyGraph.from(result.getRoot());

      for (ArtifactResult artifactResult : result.getArtifactResults()) {
        Artifact resolvedArtifact = artifactResult.getArtifact();

        if (resolvedArtifact == null) {
          Artifact requestedArtifact = artifactResult.getRequest().getArtifact();
          graph.addUnresolvableArtifactProblem(requestedArtifact);
        }
      }
      
      return graph;
    }
  }

  private enum GraphTraversalOption {
    /** Normal Maven dependency graph */
    MAVEN,

    /** The full dependency graph */
    FULL;
  }

}
