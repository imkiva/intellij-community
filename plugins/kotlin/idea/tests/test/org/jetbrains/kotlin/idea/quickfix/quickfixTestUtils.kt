// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix.utils

import java.io.File

fun findInspectionFile(startDir: File): File? {
    var currentDir: File? = startDir
    while (currentDir != null) {
        val inspectionFile = File(currentDir, ".inspection")
        if (inspectionFile.exists()) {
            return inspectionFile
        }
        currentDir = currentDir.parentFile
    }
    return null
}
