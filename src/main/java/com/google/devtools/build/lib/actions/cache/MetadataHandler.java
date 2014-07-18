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
package com.google.devtools.build.lib.actions.cache;

import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.vfs.FileStatus;

import java.io.IOException;
import java.util.Collection;

/** Retrieves {@link Metadata} of {@link Artifact}s, and inserts virtual metadata as well. */
public interface MetadataHandler {
  /**
   * Returns metadata for the given artifact or null if it does not exist.
   *
   * @param artifact artifact
   *
   * @return metadata instance or null if metadata cannot be obtained.
   */
  Metadata getMetadataMaybe(Artifact artifact);
  /**
   * Returns metadata for the given artifact or throws an exception if the
   * metadata could not be obtained.
   *
   * @return metadata instance
   *
   * @throws IOException if metadata could not be obtained.
   */
  Metadata getMetadata(Artifact artifact) throws IOException;

  /** Sets digest for virtual artifacts (e.g. middlemen). {@code digest} must not be null. */
  void setDigestForVirtualArtifact(Artifact artifact, Digest digest);

  /**
   * Injects provided digest into the metadata handler, simultaneously caching lstat() data as well.
   */
  void injectDigest(ActionInput output, FileStatus statNoFollow, byte[] digest);

  /** Returns true iff artifact exists. */
  boolean artifactExists(Artifact artifact);

  /**
   * @return Whether the artifact's data was injected.
   * @throws IOException if implementation tried to stat artifact which threw an exception.
   *         Technically, this means that the artifact could not have been injected, but by throwing
   *         here we save the caller trying to stat this file on their own and throwing the same
   *         exception. Implementations are not guaranteed to throw in this case if they are able to
   *         determine that the artifact is not injected without statting it.
   */
  boolean isInjected(Artifact artifact) throws IOException;

  /** Discards all metadata for the given artifacts, presumably because they will be modified. */
  void discardMetadata(Collection<Artifact> artifactList);

}
