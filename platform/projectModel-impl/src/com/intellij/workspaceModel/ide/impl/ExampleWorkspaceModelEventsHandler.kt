// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.workspaceModel.ide.WorkspaceModelChangeListener
import com.intellij.workspaceModel.ide.WorkspaceModelTopics
import com.intellij.workspaceModel.ide.impl.legacyBridge.module.isModuleUnloaded
import com.intellij.workspaceModel.storage.EntityChange
import com.intellij.workspaceModel.storage.EntityStorage
import com.intellij.workspaceModel.storage.VersionedStorageChange
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.bridgeEntities.*
import com.intellij.workspaceModel.storage.bridgeEntities.api.*
import com.intellij.workspaceModel.storage.url.VirtualFileUrl

/**
 * This class is not handling events about global libraries and SDK changes. Such events should
 * be processed via [com.intellij.openapi.roots.ModuleRootListener]
 */
class ExampleWorkspaceModelEventsHandler(private val project: Project): Disposable {
  init {
    val messageBusConnection = project.messageBus.connect(this)
    WorkspaceModelTopics.getInstance(project).subscribeAfterModuleLoading(messageBusConnection, object : WorkspaceModelChangeListener {
      override fun changed(event: VersionedStorageChange) {
        // Example of handling VirtualFileUrl change at entities  with  update cache based on the changed urls
        handleLibraryChanged(event)
        handleContentRootChanged(event)
        handleSourceRootChanged(event)
        // If cache invalidation depends on specific properties e.g. [org.jetbrains.jps.model.java.JavaResourceRootProperties.getRelativeOutputPath]
        // changes from [JavaResourceRootEntity] should be handled
        handleJavaSourceRootChanged(event)
        handleJavaResourceRootChanged(event)
        handleCustomSourceRootPropertiesChanged(event)

        handleModuleChanged(event)
        handleJavaModuleSettingsChanged(event)
        handleLibraryPropertiesChanged(event)
        handleModuleGroupPathChanged(event)
        handleModuleCustomImlDataChanged(event)
      }
    })
  }

  private fun handleLibraryChanged(event: VersionedStorageChange) {
    event.getChanges(LibraryEntity::class.java).forEach { change ->
      val (entity, _) = getEntityAndStorage(event, change)
      if (!libraryIsDependency(entity, project)) return@forEach
      val (addedUrls, removedUrls) = calculateChangedUrls(change, listOf(), listOf({ roots.map { it.url }}, { excludedRoots }))
      updateCache(addedUrls.urls, removedUrls.urls)
    }
  }

  /**
   * If your cache invalidation depends on [com.intellij.openapi.roots.impl.libraries.LibraryEx.getKind] or
   * [com.intellij.openapi.roots.impl.libraries.LibraryEx.getProperties] you should handle changes from [LibraryPropertiesEntity]
   */
  private fun handleLibraryPropertiesChanged(event: VersionedStorageChange) {
    event.getChanges(LibraryPropertiesEntity::class.java).forEach { change ->
      val (entity, _) = getEntityAndStorage(event, change)
      if (!libraryIsDependency(entity.library, project)) return@forEach
      updateCache()
    }
  }

  private fun handleContentRootChanged(event: VersionedStorageChange) {
    event.getChanges(ContentRootEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ module }) return@forEach
      val (addedUrls, removedUrls) = calculateChangedUrls(change, listOf({url}), listOf({ excludedUrls }))
      updateCache(addedUrls.urls, removedUrls.urls)
    }
  }

  private fun handleSourceRootChanged(event: VersionedStorageChange) {
    event.getChanges(SourceRootEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ contentRoot.module }) return@forEach
      val (addedUrls, removedUrls) = calculateChangedUrls(change, listOf({url}))
      updateCache(addedUrls.urls, removedUrls.urls)
    }
  }

  private fun handleJavaModuleSettingsChanged(event: VersionedStorageChange) {
    event.getChanges(JavaModuleSettingsEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ module }) return@forEach
      val (addedUrls, removedUrls) = calculateChangedUrls(change, listOf({compilerOutput}, {compilerOutputForTests}))
      updateCache(addedUrls.urls, removedUrls.urls)
    }
  }

  private fun handleModuleChanged(event: VersionedStorageChange) {
    event.getChanges(ModuleEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ this }) return@forEach
      updateCache()
    }
  }

  private fun handleModuleGroupPathChanged(event: VersionedStorageChange) {
    event.getChanges(ModuleGroupPathEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ module }) return@forEach
      updateCache()
    }
  }

  private fun handleModuleCustomImlDataChanged(event: VersionedStorageChange) {
    event.getChanges(ModuleCustomImlDataEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ module }) return@forEach
      updateCache()
    }
  }

  private fun handleJavaSourceRootChanged(event: VersionedStorageChange) {
    event.getChanges(JavaSourceRootEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change){ sourceRoot.contentRoot.module }) return@forEach
      updateCache()
    }
  }

  /**
   * If cache invalidation depends on specific properties e.g. [org.jetbrains.jps.model.java.JavaResourceRootProperties.getRelativeOutputPath]
   * changes from [JavaResourceRootEntity] should be handled
   */
  private fun handleJavaResourceRootChanged(event: VersionedStorageChange) {
    event.getChanges(JavaResourceRootEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change) { sourceRoot.contentRoot.module }) return@forEach
      updateCache()
    }
  }

  private fun handleCustomSourceRootPropertiesChanged(event: VersionedStorageChange) {
    event.getChanges(CustomSourceRootPropertiesEntity::class.java).forEach { change ->
      if (isInUnloadedModule(event, change) { sourceRoot.contentRoot.module }) return@forEach
      updateCache()
    }
  }

  private fun <T: WorkspaceEntity> getEntityAndStorage(event: VersionedStorageChange, change: EntityChange<T>): Pair<T, EntityStorage> {
    return when (change) {
      is EntityChange.Added -> change.entity to event.storageAfter
      is EntityChange.Removed -> change.entity to event.storageBefore
      is EntityChange.Replaced -> change.newEntity to event.storageAfter
    }
  }

  private fun <T: WorkspaceEntity> isInUnloadedModule(event: VersionedStorageChange, change: EntityChange<T>, moduleAssessor: T.() -> ModuleEntity): Boolean {
    val (entity, storage) = getEntityAndStorage(event, change)
    return entity.moduleAssessor().isModuleUnloaded(storage)
  }

  /**
   * Example of method for calculation changed VirtualFileUrls at entities
   */
  private fun <T: WorkspaceEntity> calculateChangedUrls(change: EntityChange<T>, vfuAssessors: List<T.() -> VirtualFileUrl?>,
                                                        vfuListAssessors: List<T.() -> List<VirtualFileUrl>> = emptyList()): Pair<Added, Removed> {
    val addedVirtualFileUrls = mutableSetOf<VirtualFileUrl>()
    val removedVirtualFileUrls = mutableSetOf<VirtualFileUrl>()

    vfuAssessors.forEach { fieldAssessor ->
      when (change) {
        is EntityChange.Added -> fieldAssessor.invoke(change.entity)?.also { addedVirtualFileUrls.add(it) }
        is EntityChange.Removed -> fieldAssessor.invoke(change.entity)?.also { removedVirtualFileUrls.add(it) }
        is EntityChange.Replaced -> {
          val newVirtualFileUrl = fieldAssessor.invoke(change.newEntity)
          val oldVirtualFileUrl = fieldAssessor.invoke(change.oldEntity)
          if (newVirtualFileUrl != oldVirtualFileUrl) {
            newVirtualFileUrl?.also { addedVirtualFileUrls.add(it) }
            oldVirtualFileUrl?.also { removedVirtualFileUrls.add(it) }
          }
        }
      }
    }

    vfuListAssessors.forEach { fieldAssessor ->
      when (change) {
        is EntityChange.Added -> {
          val virtualFileUrls = fieldAssessor.invoke(change.entity)
          if (virtualFileUrls.isNotEmpty()) {
            addedVirtualFileUrls.addAll(virtualFileUrls)
          }
        }
        is EntityChange.Removed -> {
          val virtualFileUrls = fieldAssessor.invoke(change.entity)
          if (virtualFileUrls.isNotEmpty()) {
            removedVirtualFileUrls.addAll(virtualFileUrls)
          }
        }
        is EntityChange.Replaced -> {
          val newVirtualFileUrls = fieldAssessor.invoke(change.newEntity).toSet()
          val oldVirtualFileUrls = fieldAssessor.invoke(change.oldEntity).toSet()
          addedVirtualFileUrls.addAll(newVirtualFileUrls.subtract(oldVirtualFileUrls))
          removedVirtualFileUrls.addAll(oldVirtualFileUrls.subtract(newVirtualFileUrls))
        }
      }
    }
    return Added(addedVirtualFileUrls) to Removed(removedVirtualFileUrls)
  }

  private fun libraryIsDependency(library: LibraryEntity, project: Project): Boolean {
    if (library.tableId is LibraryTableId.ModuleLibraryTableId) return true
    val libraryName = library.name
    ModuleManager.getInstance(project).modules.forEach { module ->
      val exists = ModuleRootManager.getInstance(module).orderEntries.any { it is LibraryOrderEntry && it.libraryName == libraryName }
      if (exists) return true
    }
    return false
  }

  private fun updateCache() { }
  private fun updateCache(addedVirtualFileUrls: MutableSet<VirtualFileUrl>,
                          removedVirtualFileUrls: MutableSet<VirtualFileUrl>) { }


  private data class Added(val urls: MutableSet<VirtualFileUrl>)
  private data class Removed(val urls: MutableSet<VirtualFileUrl>)

  override fun dispose() { }
}