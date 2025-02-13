// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.updateSettings.impl.UpdateChecker.excludedFromUpdateCheckPlugins
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.idea.KotlinPluginCompatibilityVerifier.checkCompatibility
import org.jetbrains.kotlin.idea.configuration.notifications.notifyKotlinStyleUpdateIfNeeded
import org.jetbrains.kotlin.idea.configuration.notifications.showEapSurveyNotification
import org.jetbrains.kotlin.idea.core.KotlinPluginDisposable
import org.jetbrains.kotlin.idea.reporter.KotlinReportSubmitter.Companion.setupReportingFromRelease
import org.jetbrains.kotlin.idea.search.containsKotlinFile
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import java.util.concurrent.Callable

internal class PluginStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        excludedFromUpdateCheckPlugins.add("org.jetbrains.kotlin")
        checkCompatibility()
        setupReportingFromRelease()

        //todo[Sedunov]: wait for fix in platform to avoid misunderstood from Java newbies (also ConfigureKotlinInTempDirTest)
        //KotlinSdkType.Companion.setUpIfNeeded();

        ReadAction.nonBlocking(Callable { project.containsKotlinFile() })
            .inSmartMode(project)
            .expireWith(KotlinPluginDisposable.getInstance(project))
            .finishOnUiThread(ModalityState.any()) { hasKotlinFiles ->
                if (!hasKotlinFiles) return@finishOnUiThread

                if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
                    notifyKotlinStyleUpdateIfNeeded(project)

                    if (!isUnitTestMode()) {
                        showEapSurveyNotification(project)
                    }
                }

                val daemonCodeAnalyzer = DaemonCodeAnalyzerImpl.getInstanceEx(project) as DaemonCodeAnalyzerImpl
                daemonCodeAnalyzer.serializeCodeInsightPasses(true)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}