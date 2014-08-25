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

package com.google.devtools.build.lib.rules.cpp;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.RuleContext;

/**
 * Pluggable C++ compilation semantics.
 */
public interface CppSemantics {
  /**
   * Returns the "effective source path" of a source file.
   *
   * <p>It is used, among other things, for computing the output path.
   */
  PathFragment getEffectiveSourcePath(Artifact source);

  /**
   * Called before a C++ compile action is built.
   *
   * <p>Gives the semantics implementation the opportunity to change compile actions at the last
   * minute.
   */
  void finalizeCompileActionBuilder(
      RuleContext ruleContext, CppCompileActionBuilder actionBuilder);

  /**
   * Called before {@link CppCompilationContext}s are finalized.
   *
   * <p>Gives the semantics implementation the opportunity to change what the C++ rule propagates
   * to dependent rules.
   */
  void setupCompilationContext(
      RuleContext ruleContext, CppCompilationContext.Builder contextBuilder);
}
