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
package com.google.devtools.build.lib.view.test;

import static com.google.devtools.build.lib.view.RunfilesProvider.RunfilesProviderImpl.dataSpecificRunfilesProvider;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.packages.TestTargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.view.GenericRuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.view.RuleConfiguredTarget;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.Runfiles;
import com.google.devtools.build.lib.view.RunfilesCollector;
import com.google.devtools.build.lib.view.RunfilesProvider;
import com.google.devtools.build.lib.view.TransitiveInfoCollection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Implementation for the "test_suite" rule.
 */
public class TestSuite implements RuleConfiguredTargetFactory {

  @Override
  public RuleConfiguredTarget create(RuleContext ruleContext) {
    checkTestsAndSuites(ruleContext, "tests");
    checkTestsAndSuites(ruleContext, "suites");
    if (ruleContext.hasErrors()) {
      return null;
    }

    //
    //  CAUTION!  Keep this logic consistent with lib.query2.TestsExpression!
    //

    List<String> tagsAttribute = new ArrayList<>(
        ruleContext.attributes().get("tags", Type.STRING_LIST));
    tagsAttribute.remove("manual");
    Pair<Collection<String>, Collection<String>> requiredExcluded =
        TestTargetUtils.sortTagsBySense(tagsAttribute);

    List<TransitiveInfoCollection> directTestsAndSuitesBuilder = new ArrayList<>();

    // The set of implicit tests is determined in
    // {@link com.google.devtools.build.lib.packages.Package}.
    // Manual tests are already filtered out there. That is what $implicit_tests is about.
    for (TransitiveInfoCollection dep :
          Iterables.concat(
              ruleContext.getPrerequisites("tests", Mode.TARGET),
              ruleContext.getPrerequisites("suites", Mode.TARGET),
              ruleContext.getPrerequisites("$implicit_tests", Mode.TARGET))) {
      if (dep.getProvider(TestProvider.class) != null) {
        List<String> tags = dep.getProvider(TestProvider.class).getTestTags();
        if (!TestTargetUtils.testMatchesFilters(
            tags, requiredExcluded.first, requiredExcluded.second, true)) {
          // This test does not match our filter. Ignore it.
          continue;
        }
      }
      directTestsAndSuitesBuilder.add(dep);
    }

    Runfiles runfiles = new Runfiles.Builder()
        .addTargets(RunfilesCollector.State.DATA, directTestsAndSuitesBuilder)
        .build();

    return new GenericRuleConfiguredTargetBuilder(ruleContext)
        .add(RunfilesProvider.class, dataSpecificRunfilesProvider(Runfiles.EMPTY, runfiles))
        .add(TransitiveTestsProvider.class, new TransitiveTestsProviderImpl())
        .build();
  }

  private void checkTestsAndSuites(RuleContext ruleContext, String attributeName) {
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites(attributeName, Mode.TARGET)) {
      // TODO(bazel-team): Maybe convert the TransitiveTestsProvider into an inner interface.
      TransitiveTestsProvider provider = dep.getProvider(TransitiveTestsProvider.class);
      TestProvider testProvider = dep.getProvider(TestProvider.class);
      if (provider == null && testProvider == null) {
        ruleContext.attributeError(attributeName,
            "expecting a test or a test_suite rule but '" + dep.getLabel() + "' is not one");
      }
    }
  }

  private static final class TransitiveTestsProviderImpl implements TransitiveTestsProvider {
  }
}
