// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.structureView

import com.intellij.ide.util.FileStructurePopup
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.idea.completion.test.configureWithExtraFile
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.idea.test.InTextDirectivesUtils
import java.io.File

abstract class AbstractKotlinFileStructureTest : KotlinFileStructureTestBase() {
    override fun getProjectDescriptor() = KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE

    override val fileExtension = "kt"
    override val treeFileName: String get() = getFileName("after")

    fun doTest(path: String) {
        myFixture.configureWithExtraFile(path)

        popupFixture.popup.setup()

        checkTree()
    }

    protected fun FileStructurePopup.setup() {
        val fileText = FileUtil.loadFile(File(testDataPath, fileName()), true)

        val withInherited = InTextDirectivesUtils.isDirectiveDefined(fileText, "WITH_INHERITED")
        setTreeActionState(KotlinInheritedMembersNodeProvider::class.java, withInherited)
    }
}
