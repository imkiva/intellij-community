// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.idea;

import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import org.jetbrains.annotations.NotNull;

public final class CommandLineArgs {
  public static final String DISABLE_NON_BUNDLED_PLUGINS = "disableNonBundledPlugins";
  public static final String DONT_REOPEN_PROJECTS = "dontReopenProjects";
  public static final String SPLASH = "splash";
  @SuppressWarnings("SpellCheckingInspection")
  public static final String NO_SPLASH = "nosplash";

  public static void parse(String[] args) {
    for (String arg : args) {
      if (arg.equalsIgnoreCase(DISABLE_NON_BUNDLED_PLUGINS)) {
        IdeaPluginDescriptorImpl.disableNonBundledPlugins = true;
      }
      else if (arg.equalsIgnoreCase(DONT_REOPEN_PROJECTS)) {
        RecentProjectsManagerBase.dontReopenProjects = true;
      }
    }
  }

  public static boolean isKnownArgument(@NotNull String arg) {
    return SPLASH.equals(arg) || NO_SPLASH.equals(arg) || DISABLE_NON_BUNDLED_PLUGINS.equalsIgnoreCase(arg) || DONT_REOPEN_PROJECTS.equalsIgnoreCase(arg);
  }
}
