/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o. and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.panels.management.packages.columns.renderers

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.components.JBComboBoxLabel
import com.jetbrains.packagesearch.intellij.plugin.ui.PackageSearchUI
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.PackageScope
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.panels.management.packages.PackagesTableItem
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.panels.management.packages.columns.colors
import net.miginfocom.swing.MigLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

internal object PackageScopeTableCellRenderer : TableCellRenderer {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) = JPanel(MigLayout("al left center, insets 0 8 0 0")).apply {
        table.colors.applyTo(this, isSelected)

        val bgColor = if (!isSelected && value is PackagesTableItem.InstallablePackage) {
            PackageSearchUI.ListRowHighlightBackground
        } else {
            background
        }

        background = bgColor

        val jbComboBoxLabel = JBComboBoxLabel().apply {
            table.colors.applyTo(this, isSelected)
            background = bgColor
            icon = AllIcons.General.LinkDropTriangle

            text = when (value) {
                is PackagesTableItem<*> -> value.uiPackageModel.selectedScope.displayName
                else -> throw IllegalArgumentException("The value is expected to be a PackagesTableItem, but wasn't.")
            }
        }
        add(jbComboBoxLabel)
    }

    @NlsSafe
    private fun scopesMessage(installedScopes: List<PackageScope>, defaultScope: PackageScope): String {
        if (installedScopes.isEmpty()) return defaultScope.displayName

        return installedScopes.joinToString { it.displayName }
    }
}
