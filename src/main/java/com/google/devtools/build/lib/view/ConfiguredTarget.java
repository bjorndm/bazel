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

package com.google.devtools.build.lib.view;

import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.view.config.BuildConfiguration;

/**
 * A {@link ConfiguredTarget} is conceptually a {@link TransitiveInfoCollection} coupled
 * with the {@link Target} and {@link BuildConfiguration} objects it was created from.
 *
 * <p>This interface is supposed to only be used in {@link BuildView} and above. In particular,
 * rule implementations should not be able to access the {@link ConfiguredTarget} objects
 * associated with their direct dependencies, only the corresponding
 * {@link TransitiveInfoCollection}s. Also, {@link ConfiguredTarget} objects should not be
 * accessible from the action graph.
 */
public interface ConfiguredTarget extends TransitiveInfoCollection {
  /**
   * Returns the Target with which this {@link ConfiguredTarget} is associated.
   */
  Target getTarget();

  /**
   * <p>Returns the {@link BuildConfiguration} for which this {@link ConfiguredTarget} is
   * defined. Configuration is defined for all configured targets with exception
   * of the {@link InputFileConfiguredTarget} for which it is always
   * <b>null</b>.</p>
   */
  BuildConfiguration getConfiguration();
}
