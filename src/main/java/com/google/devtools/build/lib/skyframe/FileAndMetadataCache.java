// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.cache.Digest;
import com.google.devtools.build.lib.actions.cache.DigestUtils;
import com.google.devtools.build.lib.actions.cache.Metadata;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

/**
 * Cache provided by an {@link ActionExecutionNodeBuilder}, allowing Blaze to obtain data from the
 * graph and to inject data (e.g. file digests) back into the graph.
 *
 * <p>Data for the action's inputs is injected into this cache on construction, using the graph as
 * the source of truth.
 *
 * <p>As well, this cache collects data about the action's output files, which is used in three
 * ways. First, it is served as requested during action execution, primarily by the {@code
 * ActionCacheChecker} when determining if the action must be rerun, and then after the action is
 * run, to gather information about the outputs. Second, it is accessed by {@link
 * ArtifactNodeBuilder}s in order to construct {@link ArtifactNode}s. Third, the {@link
 * FilesystemNodeChecker} uses it to determine the set of output files to check for inter-build
 * modifications. Because all these use cases are slightly different, we must occasionally store two
 * versions of the data for a node (see {@link #getAdditionalOutputData} for more.
 */
class FileAndMetadataCache implements ActionInputFileCache, MetadataHandler {
  private final Map<Artifact, FileArtifactNode> inputArtifactData;
  private final Map<Artifact, Collection<Artifact>> expandedInputMiddlemen;
  private final File execRoot;
  private final Map<ByteString, Artifact> reverseMap = new ConcurrentHashMap<>();
  private final ConcurrentMap<Artifact, FileNode> outputArtifactData =
      new ConcurrentHashMap<>();
  // See #getAdditionalOutputData for documentation of this field.
  private final ConcurrentMap<Artifact, FileArtifactNode> additionalOutputData =
      new ConcurrentHashMap<>();
  private final Set<Artifact> injectedArtifacts = Sets.newConcurrentHashSet();
  private final TimestampGranularityMonitor tsgm;

  FileAndMetadataCache(Map<Artifact, FileArtifactNode> inputArtifactData,
      Map<Artifact, Collection<Artifact>> expandedInputMiddlemen, File execRoot,
      TimestampGranularityMonitor tsgm) {
    this.inputArtifactData = Preconditions.checkNotNull(inputArtifactData);
    this.expandedInputMiddlemen = Preconditions.checkNotNull(expandedInputMiddlemen);
    this.execRoot = Preconditions.checkNotNull(execRoot);
    this.tsgm = tsgm;
  }

  @Override
  public Metadata getMetadataMaybe(Artifact artifact) {
    try {
      return getMetadata(artifact);
    } catch (IOException e) {
      return null;
    }
  }

  private static Metadata metadataFromNode(FileArtifactNode node) {
    // If the file is empty or a directory, we need to return the mtime because the action cache
    // uses mtime to determine if this artifact has changed.  We do not optimize for this code
    // path (by storing the mtime somewhere) because we eventually may be switching to use digests
    // for empty files. We want this code path to go away somehow too for directories (maybe by
    // implementing FileSet in Skyframe).
    return node.getSize() > 0
        ? new Metadata(0L, node.getDigest())
        : new Metadata(node.getModifiedTime(), null);
  }

  @Override
  public Metadata getMetadata(Artifact artifact) throws IOException {
    Metadata metadata = getRealMetadata(artifact);
    return artifact.forceConstantMetadata() ? Metadata.CONSTANT_METADATA : metadata;
  }

  /**
   * We cache data for constant-metadata artifacts, even though it is technically unnecessary,
   * because the data stored in this cache is consumed by various parts of Blaze via the {@link
   * ActionExecutionNode} (for now, {@link FilesystemNodeChecker} and {@link ArtifactNodeBuilder}).
   * It is simpler for those parts if every output of the action is present in the cache. However,
   * we must not return the actual metadata for a constant-metadata artifact.
   */
  private Metadata getRealMetadata(Artifact artifact) throws IOException {
    FileArtifactNode node = inputArtifactData.get(artifact);
    if (node != null) {
      return metadataFromNode(node);
    }
    if (artifact.isSourceArtifact()) {
      // We might have no artifact data for discovered headers, and it's not worth storing it in
      // this cache, because it won't be reused across actions.
      return null;
    }
    if (artifact.isMiddlemanArtifact()) {
      // A middleman artifact's data was either already injected from the action cache checker using
      // #setDigestForVirtualArtifact, or it has the default middleman value.
      node = additionalOutputData.get(artifact);
      if (node != null) {
        return metadataFromNode(node);
      }
      node = FileArtifactNode.DEFAULT_MIDDLEMAN;
      FileArtifactNode oldNode = additionalOutputData.putIfAbsent(artifact, node);
      checkInconsistentData(artifact, oldNode, node);
      return metadataFromNode(node);
    }
    FileNode fileNode = outputArtifactData.get(artifact);
    if (fileNode != null) {
      // Non-middleman artifacts should only have additionalOutputData if they have
      // outputArtifactData. We don't assert this because of concurrency possibilities, but at least
      // we don't check additionalOutputData unless we expect that we might see the artifact there.
      node = additionalOutputData.get(artifact);
      // If additional output data is present for this artifact, we use it in preference to the
      // usual calculation.
      return node != null
          ? metadataFromNode(node)
          : new Metadata(0L, Preconditions.checkNotNull(fileNode.getDigest(), artifact));
    }
    // We do not cache exceptions besides nonexistence here, because it is unlikely that the file
    // will be requested from this cache too many times.
    fileNode = fileNodeFromArtifact(artifact, null, tsgm);
    FileNode oldFileNode = outputArtifactData.putIfAbsent(artifact, fileNode);
    checkInconsistentData(artifact, oldFileNode, node);
    return maybeStoreAdditionalData(artifact, fileNode, null);
  }

  /** Expands one of the input middlemen artifacts of the corresponding action. */
  public Collection<Artifact> expandInputMiddleman(Artifact middlemanArtifact) {
    Preconditions.checkState(middlemanArtifact.isMiddlemanArtifact(), middlemanArtifact);
    Collection<Artifact> result = expandedInputMiddlemen.get(middlemanArtifact);
    // Note that result may be null for non-aggregating middlemen.
    return result == null ? ImmutableSet.<Artifact>of() : result;
  }

  /**
   * Check that the new {@code data} we just calculated for an {@code artifact} agrees with the
   * {@code oldData} (presumably calculated concurrently), if it was present.
   */
  // Not private only because used by SkyframeActionExecutor's metadata handler.
  static void checkInconsistentData(Artifact artifact,
      @Nullable Object oldData, Object data) throws IOException {
    if (oldData != null && !oldData.equals(data)) {
      // Another thread checked this file since we looked at the map, and got a different answer
      // than we did. Presumably the user modified the file between reads.
      throw new IOException("Data for " + artifact.prettyPrint() + " changed to " + data
          + " after it was calculated as " + oldData);
    }
  }

  /**
   * See {@link #getAdditionalOutputData} for why we sometimes need to store additional data, even
   * for normal (non-middleman) artifacts.
   */
  @Nullable
  private Metadata maybeStoreAdditionalData(Artifact artifact, FileNode data,
      @Nullable byte[] injectedDigest) throws IOException {
    if (!data.exists()) {
      // Nonexistent files should only occur before executing an action.
      throw new FileNotFoundException(artifact.prettyPrint() + " does not exist");
    }
    boolean isFile = data.isFile();
    boolean useDigest = DigestUtils.useFileDigest(artifact, isFile, isFile ? data.getSize() : 0);
    if (useDigest && data.getDigest() != null) {
      // We do not need to store the FileArtifactNode separately -- the digest is in the file node
      // and that is all that is needed for this file's metadata.
      return new Metadata(0L, data.getDigest());
    }
    // Unfortunately, the FileNode does not contain enough information for us to calculate the
    // corresponding FileArtifactNode -- either the metadata must use the modified time, which we do
    // not expose in the FileNode, or the FileNode didn't store the digest So we store the metadata
    // separately.
    // Use the FileNode's digest if no digest was injected, or if the file can't be digested.
    injectedDigest = injectedDigest != null || !isFile ? injectedDigest : data.getDigest();
    FileArtifactNode node =
        FileArtifactNode.create(artifact, isFile, isFile ? data.getSize() : 0, injectedDigest);
    FileArtifactNode oldNode = additionalOutputData.putIfAbsent(artifact, node);
    checkInconsistentData(artifact, oldNode, node);
    return metadataFromNode(node);
  }

  @Override
  public void setDigestForVirtualArtifact(Artifact artifact, Digest digest) {
    Preconditions.checkState(artifact.isMiddlemanArtifact(), artifact);
    Preconditions.checkNotNull(digest, artifact);
    additionalOutputData.put(artifact,
        FileArtifactNode.createMiddleman(digest.asMetadata().digest));
  }

  @Override
  public void injectDigest(ActionInput output, FileStatus statNoFollow, byte[] digest) {
    if (output instanceof Artifact) {
      Artifact artifact = (Artifact) output;
      Preconditions.checkState(injectedArtifacts.add(artifact), artifact);
      FileNode fileNode;
      try {
        // This call may do an unnecessary call to Path#getFastDigest to see if the digest is
        // readily available. We cannot pass the digest in, though, because if it is not available
        // from the filesystem, this FileNode will not compare equal to another one created for the
        // same file, because the other one will be missing its digest.
        fileNode = fileNodeFromArtifact(artifact, FileStatusWithDigestAdapter.adapt(statNoFollow),
            tsgm);
        byte[] fileDigest = fileNode.getDigest();
        Preconditions.checkState(fileDigest == null || Arrays.equals(digest, fileDigest),
            "%s %s %s", artifact, digest, fileDigest);
        outputArtifactData.put(artifact, fileNode);
      } catch (IOException e) {
        // Do nothing - we just failed to inject metadata. Real error handling will be done later,
        // when somebody will try to access that file.
        return;
      }
      // If needed, insert additional data. Note that this can only be true if the file is empty or
      // the filesystem does not support fast digests. Since we usually only inject digests when
      // running with a filesystem that supports fast digests, this is fairly unlikely.
      try {
        maybeStoreAdditionalData(artifact, fileNode, digest);
      } catch (IOException e) {
        if (fileNode.getSize() != 0) {
          // Empty files currently have their mtimes examined, and so could throw. No other files
          // should throw, since all filesystem access has already been done.
          throw new IllegalStateException(
              "Filesystem should not have been accessed while injecting data for "
          + artifact.prettyPrint(), e);
        }
        // Ignore exceptions for empty files, as above.
      }
    }
  }

  @Override
  public void discardMetadata(Collection<Artifact> artifactList) {
    Preconditions.checkState(injectedArtifacts.isEmpty(),
        "Artifacts cannot be injected before action execution: %s", injectedArtifacts);
    outputArtifactData.keySet().removeAll(artifactList);
    additionalOutputData.keySet().removeAll(artifactList);
  }

  @Override
  public boolean artifactExists(Artifact artifact) {
    return getMetadataMaybe(artifact) != null;
  }

  @Override
  public boolean isInjected(Artifact artifact) {
    return injectedArtifacts.contains(artifact);
  }

  /**
   * @return data for output files that was computed during execution. Should include data for all
   * non-middleman artifacts.
   */
  Map<Artifact, FileNode> getOutputData() {
    return outputArtifactData;
  }

  /**
   * Returns data for any output files whose metadata was not computable from the corresponding
   * entry in {@link #getOutputData}.
   *
   * <p>There are three reasons why we might not be able to compute metadata for an artifact from
   * the FileNode. First, middleman artifacts have no corresponding FileNodes. Second, if computing
   * a file's digest is not fast, the FileNode does not do so, so a file on a filesystem without
   * fast digests has to have its metadata stored separately. Third, some files' metadata
   * (directories, empty files) contain their mtimes, which the FileNode does not expose, so that
   * has to be stored separately.
   *
   * <p>Note that for files that need digests, we can't easily inject the digest in the FileNode
   * because it would complicate equality-checking on subsequent builds -- if our filesystem doesn't
   * do fast digests, the comparison node would not have a digest.
   */
  Map<Artifact, FileArtifactNode> getAdditionalOutputData() {
    return additionalOutputData;
  }

  @Override
  public long getSizeInBytes(ActionInput input) throws IOException {
    FileArtifactNode metadata = inputArtifactData.get(input);
    if (metadata != null) {
      return metadata.getSize();
    }
    return -1;
  }

  @Nullable
  @Override
  public File getFileFromDigest(ByteString digest) throws IOException {
    Artifact artifact = reverseMap.get(digest);
    if (artifact != null) {
      String relPath = artifact.getExecPathString();
      return relPath.startsWith("/") ? new File(relPath) : new File(execRoot, relPath);
    }
    return null;
  }

  @Nullable
  @Override
  public ByteString getDigest(ActionInput input) throws IOException {
    FileArtifactNode metadata = inputArtifactData.get(input);
    if (metadata != null) {
      byte[] bytes = metadata.getDigest();
      if (bytes != null) {
        ByteString digest = ByteString.copyFrom(BaseEncoding.base16().lowerCase().encode(bytes)
            .getBytes(StandardCharsets.US_ASCII));
        reverseMap.put(digest, (Artifact) input);
        return digest;
      }
    }
    return null;
  }

  @Override
  public boolean contentsAvailableLocally(ByteString digest) {
    return reverseMap.containsKey(digest);
  }

  static FileNode fileNodeFromArtifact(Artifact artifact,
      @Nullable FileStatusWithDigest statNoFollow, TimestampGranularityMonitor tsgm)
          throws IOException {
    Path path = artifact.getPath();
    RootedPath rootedPath =
        RootedPath.toRootedPath(artifact.getRoot().getPath(), artifact.getRootRelativePath());
    if (statNoFollow == null) {
      statNoFollow = FileStatusWithDigestAdapter.adapt(path.statIfFound(Symlinks.NOFOLLOW));
      if (statNoFollow == null) {
        return FileNode.node(rootedPath, FileStateNode.NONEXISTENT_FILE_STATE_NODE,
            rootedPath, FileStateNode.NONEXISTENT_FILE_STATE_NODE);
      }
    }
    Path realPath = path;
    // We use FileStatus#isSymbolicLink over Path#isSymbolicLink to avoid the unnecessary stat
    // done by the latter.
    if (statNoFollow.isSymbolicLink()) {
      realPath = path.resolveSymbolicLinks();
      // We need to protect against symlink cycles since FileNode#node assumes it's dealing with a
      // file that's not in a symlink cycle.
      if (realPath.equals(path)) {
        throw new IOException("symlink cycle");
      }
    }
    RootedPath realRootedPath = RootedPath.toRootedPathMaybeUnderRoot(realPath,
        ImmutableList.of(artifact.getRoot().getPath()));
    FileStateNode fileStateNode;
    FileStateNode realFileStateNode;
    try {
      fileStateNode = FileStateNode.createWithStatNoFollow(rootedPath, statNoFollow, tsgm);
      // TODO(bazel-devel): consider avoiding a 'stat' here when the symlink target hasn't changed
      // and is a source file (since changes to those are checked separately).
      realFileStateNode = realPath.equals(path) ? fileStateNode
          : FileStateNode.create(realRootedPath, tsgm);
    } catch (InconsistentFilesystemException e) {
      throw new IOException(e);
    }
    return FileNode.node(rootedPath, fileStateNode, realRootedPath, realFileStateNode);
  }
}
