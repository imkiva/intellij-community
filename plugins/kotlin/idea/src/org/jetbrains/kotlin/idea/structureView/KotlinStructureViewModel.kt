// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.structureView

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.impl.java.VisibilitySorter
import com.intellij.ide.util.treeView.smartTree.*
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.util.PlatformIcons
import org.jetbrains.kotlin.idea.KotlinIdeaBundle
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isPropertyParameter

class KotlinStructureViewModel(ktFile: KtFile, editor: Editor?) :
    StructureViewModelBase(ktFile, editor, KotlinStructureViewElement(ktFile, false)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(KotlinVisibilitySorter, Sorter.ALPHA_SORTER)
    }

    override fun isSuitable(element: PsiElement?): Boolean = element is KtDeclaration &&
            element !is KtPropertyAccessor &&
            element !is KtFunctionLiteral &&
            !(element is KtProperty && element.parent !is KtFile && element.containingClassOrObject !is KtNamedDeclaration) &&
            !(element is KtFunction && element.parent !is KtFile && element.containingClassOrObject !is KtNamedDeclaration)

    override fun getNodeProviders() = NODE_PROVIDERS

    override fun getFilters() = FILTERS

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return (value is KtClassOrObject && value !is KtEnumEntry) || value is KtFile
    }

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        // Local declarations can in any other declaration
        return false
    }

    companion object {
        private val NODE_PROVIDERS = listOf(KotlinInheritedMembersNodeProvider())
        private val FILTERS = arrayOf(PropertiesFilter, PublicElementsFilter)
    }
}

object KotlinVisibilitySorter : VisibilitySorter() {
    override fun getComparator() = Comparator<Any> { a1, a2 -> a1.accessLevel() - a2.accessLevel() }

    private fun Any.accessLevel() = (this as? KotlinStructureViewElement)?.visibility?.accessLevel ?: Int.MAX_VALUE

    override fun getName() = ID

    const val ID = "KOTLIN_VISIBILITY_SORTER"
}

object PublicElementsFilter : Filter {
    override fun isVisible(treeNode: TreeElement): Boolean {
        return (treeNode as? KotlinStructureViewElement)?.visibility?.isPublic ?: true
    }

    override fun getPresentation(): ActionPresentation {
        return ActionPresentationData(KotlinIdeaBundle.message("show.non.public"), null, PlatformIcons.PRIVATE_ICON)
    }

    override fun getName() = ID

    override fun isReverted() = true

    const val ID = "KOTLIN_SHOW_NON_PUBLIC"
}

object PropertiesFilter : Filter {
    override fun isVisible(treeNode: TreeElement): Boolean {
        val element = (treeNode as? KotlinStructureViewElement)?.element
        val isProperty = element is KtProperty && element.isMember || element is KtParameter && element.isPropertyParameter()
        return !isProperty
    }

    override fun getPresentation(): ActionPresentation {
        return ActionPresentationData(KotlinIdeaBundle.message("show.properties"), null, PlatformIcons.PROPERTY_ICON)
    }

    override fun getName() = ID

    override fun isReverted() = true

    const val ID = "KOTLIN_SHOW_PROPERTIES"
}
