// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions;

import com.intellij.CommonBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.lightEdit.LightEditCompatible;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class ShowSettingsAction extends AnAction implements DumbAware, LightEditCompatible {
  private static final Logger LOG = Logger.getInstance(ShowSettingsAction.class);

  public ShowSettingsAction() {
    super(CommonBundle.settingsAction(), CommonBundle.settingsActionDescription(), AllIcons.General.Settings);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(!ActionPlaces.isMacSystemMenuAction(e));
    if (e.getPlace().equals(ActionPlaces.WELCOME_SCREEN)) {
      e.getPresentation().setText(CommonBundle.settingsTitle());
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    perform(project != null ? project : ProjectManager.getInstance().getDefaultProject());
  }

  public static void perform(@NotNull Project project) {
    if (LOG.isDebugEnabled()) {
      final long startTime = System.nanoTime();
      // SwingUtilities must be used here
      SwingUtilities.invokeLater(() -> {
        final long endTime = System.nanoTime();
        LOG.debug("Displaying settings dialog took " + ((endTime - startTime) / 1000000) + " ms");
      });
    }

    ShowSettingsUtil.getInstance().showSettingsDialog(project, ShowSettingsUtilImpl.getConfigurableGroups(project, true));
  }
}
