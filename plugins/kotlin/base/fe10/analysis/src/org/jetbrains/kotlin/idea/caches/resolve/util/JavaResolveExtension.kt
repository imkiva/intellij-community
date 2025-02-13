// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

@file:JvmName("JavaResolutionUtils")

package org.jetbrains.kotlin.idea.caches.resolve.util

import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.decompiled.light.classes.KtLightClassForDecompiledDeclaration
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.load.java.structure.*
import org.jetbrains.kotlin.load.java.structure.impl.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver
import org.jetbrains.kotlin.resolve.scopes.MemberScope

fun PsiMethod.getJavaMethodDescriptor(): FunctionDescriptor? = javaResolutionFacade()?.let { getJavaMethodDescriptor(it) }

private fun PsiMethod.getJavaMethodDescriptor(resolutionFacade: ResolutionFacade): FunctionDescriptor? {
    val method = originalElement as? PsiMethod ?: return null
    if (method.containingClass == null || !Name.isValidIdentifier(method.name)) return null
    val resolver = method.getJavaDescriptorResolver(resolutionFacade)
    return when {
        method.isConstructor -> resolver?.resolveConstructor(JavaConstructorImpl(method))
        else -> resolver?.resolveMethod(JavaMethodImpl(method))
    }
}

fun PsiClass.getJavaClassDescriptor() = javaResolutionFacade()?.let { getJavaClassDescriptor(it) }

fun PsiClass.getJavaClassDescriptor(resolutionFacade: ResolutionFacade): ClassDescriptor? {
    val psiClass = originalElement as? PsiClass ?: return null
    return psiClass.getJavaDescriptorResolver(resolutionFacade)?.resolveClass(JavaClassImpl(psiClass))
}

private fun PsiField.getJavaFieldDescriptor(resolutionFacade: ResolutionFacade): PropertyDescriptor? {
    val field = originalElement as? PsiField ?: return null
    return field.getJavaDescriptorResolver(resolutionFacade)?.resolveField(JavaFieldImpl(field))
}

fun PsiMember.getJavaMemberDescriptor(resolutionFacade: ResolutionFacade): DeclarationDescriptor? {
    return when (this) {
        is PsiClass -> getJavaClassDescriptor(resolutionFacade)
        is PsiMethod -> getJavaMethodDescriptor(resolutionFacade)
        is PsiField -> getJavaFieldDescriptor(resolutionFacade)
        else -> null
    }
}

fun PsiMember.getJavaMemberDescriptor(): DeclarationDescriptor? = javaResolutionFacade()?.let { getJavaMemberDescriptor(it) }

fun PsiMember.getJavaOrKotlinMemberDescriptor(): DeclarationDescriptor? =
    javaResolutionFacade()?.let { getJavaOrKotlinMemberDescriptor(it) }

fun PsiMember.getJavaOrKotlinMemberDescriptor(resolutionFacade: ResolutionFacade): DeclarationDescriptor? {
    return when (val callable = unwrapped) {
        is PsiMember -> getJavaMemberDescriptor(resolutionFacade)
        is KtDeclaration -> {
            val descriptor = resolutionFacade.resolveToDescriptor(callable)
            if (descriptor is ClassDescriptor && this is PsiMethod) descriptor.unsubstitutedPrimaryConstructor else descriptor
        }
        else -> null
    }
}

fun PsiParameter.getParameterDescriptor(): ValueParameterDescriptor? = javaResolutionFacade()?.let {
    getParameterDescriptor(it)
}

fun PsiParameter.getParameterDescriptor(resolutionFacade: ResolutionFacade): ValueParameterDescriptor? {
    val method = declarationScope as? PsiMethod ?: return null
    val methodDescriptor = method.getJavaMethodDescriptor(resolutionFacade) ?: return null
    return methodDescriptor.valueParameters[parameterIndex()]
}

fun PsiClass.resolveToDescriptor(
    resolutionFacade: ResolutionFacade,
    declarationTranslator: (KtClassOrObject) -> KtClassOrObject? = { it }
): ClassDescriptor? {
    return if (this is KtLightClass && this !is KtLightClassForDecompiledDeclaration) {
        val origin = this.kotlinOrigin ?: return null
        val declaration = declarationTranslator(origin) ?: return null
        resolutionFacade.resolveToDescriptor(declaration)
    } else {
        getJavaClassDescriptor(resolutionFacade)
    } as? ClassDescriptor
}

@OptIn(FrontendInternals::class)
private fun PsiElement.getJavaDescriptorResolver(resolutionFacade: ResolutionFacade): JavaDescriptorResolver? {
    return resolutionFacade.tryGetFrontendService(this, JavaDescriptorResolver::class.java)
}

private fun JavaDescriptorResolver.resolveMethod(method: JavaMethod): FunctionDescriptor? {
    return getContainingScope(method)?.getContributedFunctions(method.name, NoLookupLocation.FROM_IDE)?.findByJavaElement(method)
}

private fun JavaDescriptorResolver.resolveConstructor(constructor: JavaConstructor): ConstructorDescriptor? {
    return resolveClass(constructor.containingClass)?.constructors?.findByJavaElement(constructor)
}

private fun JavaDescriptorResolver.resolveField(field: JavaField): PropertyDescriptor? {
    return getContainingScope(field)?.getContributedVariables(field.name, NoLookupLocation.FROM_IDE)?.findByJavaElement(field)
}

private fun JavaDescriptorResolver.getContainingScope(member: JavaMember): MemberScope? {
    val containingClass = resolveClass(member.containingClass)
    return if (member.isStatic)
        containingClass?.staticScope
    else
        containingClass?.defaultType?.memberScope
}

private fun <T : DeclarationDescriptorWithSource> Collection<T>.findByJavaElement(javaElement: JavaElement): T? {
    return firstOrNull { member ->
        val memberJavaElement = (member.original.source as? JavaSourceElement)?.javaElement
        when {
            memberJavaElement == javaElement ->
                true
            memberJavaElement is JavaElementImpl<*> && javaElement is JavaElementImpl<*> ->
                memberJavaElement.psi.isEquivalentTo(javaElement.psi)
            else ->
                false
        }
    }
}

fun PsiElement.hasJavaResolutionFacade(): Boolean = this.originalElement.containingFile != null

fun PsiElement.javaResolutionFacade() =
    KotlinCacheService.getInstance(project).getResolutionFacadeByFile(
        this.originalElement.containingFile ?: reportCouldNotCreateJavaFacade(),
        JvmPlatforms.unspecifiedJvmPlatform
    )

private fun PsiElement.reportCouldNotCreateJavaFacade(): Nothing =
    runReadAction {
        error(
            "Could not get javaResolutionFacade for element:\n" +
                    "same as originalElement = ${this === this.originalElement}" +
                    "class = ${javaClass.name}, text = $text, containingFile = ${containingFile?.name}\n" +
                    "originalElement.class = ${originalElement.javaClass.name}, originalElement.text = ${originalElement.text}), " +
                    "originalElement.containingFile = ${originalElement.containingFile?.name}"
        )
    }
