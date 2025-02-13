// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.impl

import com.intellij.workspaceModel.storage.*
import it.unimi.dsi.fastutil.Hash
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap
import org.jetbrains.annotations.TestOnly
import java.util.*

/**
 * # Replace By Source as a tree
 *
 * - Make type graphs. Separate graphs into independent parts (how is it called correctly?)
 * - Work on separate graph parts as on independent items
 * -
 */

internal class ReplaceBySourceAsTree : ReplaceBySourceOperation {

  private lateinit var targetStorage: MutableEntityStorageImpl
  private lateinit var replaceWithStorage: AbstractEntityStorage
  private lateinit var entityFilter: (EntitySource) -> Boolean

  internal val operations = HashMap<EntityId, Operation>()
  internal val addOperations = ArrayList<AddElement>()
  internal val targetState = HashMap<EntityId, ReplaceState>()
  internal val replaceWithState = HashMap<EntityId, ReplaceWithState>()

  @set:TestOnly
  internal var shuffleEntities: Long = -1L

  override fun replace(
    targetStorage: MutableEntityStorageImpl,
    replaceWithStorage: AbstractEntityStorage,
    entityFilter: (EntitySource) -> Boolean,
  ) {
    this.targetStorage = targetStorage
    this.replaceWithStorage = replaceWithStorage
    this.entityFilter = entityFilter

    val targetEntitiesToReplace = targetStorage.entitiesBySource(entityFilter)
    val replaceWithEntitiesToReplace = replaceWithStorage.entitiesBySource(entityFilter)

    val targetEntities = targetEntitiesToReplace.values.flatMap { it.values }.flatten().toMutableList()
    if (shuffleEntities != -1L && targetEntities.size > 1) {
      targetEntities.shuffleHard(Random(shuffleEntities))
    }
    for (targetEntityToReplace in targetEntities) {
      TargetProcessor().processEntity(targetEntityToReplace)
    }

    val replaceWithEntities = replaceWithEntitiesToReplace.values.flatMap { it.values }.flatten().toMutableList()
    if (shuffleEntities != -1L && replaceWithEntities.size > 1) {
      replaceWithEntities.shuffleHard(Random(shuffleEntities))
    }
    for (replaceWithEntityToReplace in replaceWithEntities) {
      ReplaceWithProcessor().processEntity(replaceWithEntityToReplace)
    }

    OperationsApplier().apply()
  }

  // This class is just a wrapper to combine functions logically
  private inner class OperationsApplier {
    fun apply() {
      val replaceToTarget = HashMap<EntityId, EntityId>()
      for (addOperation in addOperations) {
        val parents = addOperation.parents?.mapTo(HashSet()) {
          when (it) {
            is ParentsRef.AddedElement -> replaceToTarget.getValue(it.replaceWithEntityId)
            is ParentsRef.TargetRef -> it.targetEntityId
          }
        }
        addElement(parents, addOperation.replaceWithSource, replaceToTarget)
      }

      for ((id, operation) in operations) {
        when (operation) {
          is Operation.Relabel -> {
            val targetEntity = targetStorage.entityDataByIdOrDie(id).createEntity(targetStorage)
            val replaceWithEntity = replaceWithStorage.entityDataByIdOrDie(operation.replaceWithEntityId).createEntity(replaceWithStorage)
            val parents = operation.parents?.mapTo(HashSet()) {
              val targetEntityId = when (it) {
                is ParentsRef.AddedElement -> replaceToTarget.getValue(it.replaceWithEntityId)
                is ParentsRef.TargetRef -> it.targetEntityId
              }
              targetStorage.entityDataByIdOrDie(targetEntityId).createEntity(targetStorage)
            }
            targetStorage.modifyEntity(ModifiableWorkspaceEntity::class.java, targetEntity) {
              (this as ModifiableWorkspaceEntityBase<*>).relabel(replaceWithEntity, parents)
            }
            targetStorage.indexes.updateExternalMappingForEntityId(operation.replaceWithEntityId, id, replaceWithStorage.indexes)
          }
          Operation.Remove -> {
            targetStorage.removeEntityByEntityId(id)
          }
        }
      }
    }

    private fun addElement(parents: Set<EntityId>?, replaceWithDataSource: EntityId, replaceToTarget: HashMap<EntityId, EntityId>) {
      val targetParents = mutableListOf<WorkspaceEntity>()
      parents?.forEach { parent ->
        targetParents += targetStorage.entityDataByIdOrDie(parent).createEntity(targetStorage)
      }

      val entityData = replaceWithStorage.entityDataByIdOrDie(replaceWithDataSource).createDetachedEntity(targetParents)
      targetStorage.addEntity(entityData)
      targetStorage.indexes.updateExternalMappingForEntityId(replaceWithDataSource, (entityData as WorkspaceEntityBase).id,
                                                             replaceWithStorage.indexes)
      replaceToTarget[replaceWithDataSource] = entityData.id
    }
  }

  // This class is just a wrapper to combine functions logically
  private inner class ReplaceWithProcessor {
    fun processEntity(replaceWithEntity: WorkspaceEntity) {
      replaceWithEntity as WorkspaceEntityBase

      if (replaceWithState[replaceWithEntity.id] != null) return

      val trackToParents = TrackToParents(replaceWithEntity.id)
      buildRootTrack(trackToParents, replaceWithStorage)

      processEntity(trackToParents)
    }

    private fun processEntity(replaceWithTrack: TrackToParents): ParentsRef? {

      val replaceWithEntityState = replaceWithState[replaceWithTrack.entity]
      when (replaceWithEntityState) {
        ReplaceWithState.ElementMoved -> return ParentsRef.AddedElement(replaceWithTrack.entity)
        is ReplaceWithState.NoChange -> return ParentsRef.TargetRef(replaceWithTrack.entity)
        ReplaceWithState.NoChangeTraceLost -> return null
        is ReplaceWithState.Relabel -> return ParentsRef.TargetRef(replaceWithTrack.entity)
        null -> Unit
      }

      val replaceWithEntityData = replaceWithStorage.entityDataByIdOrDie(replaceWithTrack.entity)
      val replaceWithEntity = replaceWithEntityData.createEntity(replaceWithStorage)
      if (replaceWithTrack.parents.isEmpty()) {
        return findAndReplaceRootEntity(replaceWithEntity as WorkspaceEntityBase)
      }
      else {
        val parentsAssociation = replaceWithTrack.parents.mapNotNullTo(HashSet()) { processEntity(it) }
        if (parentsAssociation.isNotEmpty()) {
          val targetEntityData = parentsAssociation.filterIsInstance<ParentsRef.TargetRef>().firstNotNullOfOrNull { parent ->
            findEntityInTargetStore(replaceWithEntityData, parent.targetEntityId, replaceWithTrack.entity.clazz)
          }
          if (targetEntityData == null) {
            if (entityFilter(replaceWithEntity.entitySource)) {
              addSubtree(parentsAssociation, replaceWithTrack.entity)
              return ParentsRef.AddedElement(replaceWithTrack.entity)
            }
            else {
              replaceWithTrack.entity.addState(ReplaceWithState.NoChangeTraceLost)
              return null
            }
          }
          else {
            when {
              entityFilter(targetEntityData.entitySource) && entityFilter(replaceWithEntity.entitySource) -> {
                error("Should be already processed")
              }
              entityFilter(targetEntityData.entitySource) && !entityFilter(replaceWithEntity.entitySource) -> {
                error("Should be already processed")
              }
              !entityFilter(targetEntityData.entitySource) && entityFilter(replaceWithEntity.entitySource) -> {
                addSubtree(parentsAssociation, replaceWithTrack.entity)
                return ParentsRef.AddedElement(replaceWithTrack.entity)
              }
              !entityFilter(targetEntityData.entitySource) && !entityFilter(replaceWithEntity.entitySource) -> {
                doNothingOn(targetEntityData.createEntityId(), replaceWithTrack.entity)
                return ParentsRef.TargetRef(targetEntityData.createEntityId())
              }
              else -> error("Unexpected branch")
            }
          }
        }
        else {
          replaceWithTrack.entity.addState(ReplaceWithState.NoChangeTraceLost)
          return null
        }
      }
    }

    @Suppress("MoveVariableDeclarationIntoWhen")
    private fun findAndReplaceRootEntity(replaceWithRootEntity: WorkspaceEntityBase): ParentsRef? {
      val currentState = replaceWithState[replaceWithRootEntity.id]
      // This was just checked before this call
      assert(currentState == null)

      val targetEntity = findEntityInTargetStorage(replaceWithRootEntity)
      if (targetEntity == null) {
        if (entityFilter(replaceWithRootEntity.entitySource)) {
          addSubtree(null, replaceWithRootEntity.id)
          return ParentsRef.AddedElement(replaceWithRootEntity.id)
        }
        else {
          replaceWithRootEntity.id.addState(ReplaceWithState.NoChangeTraceLost)
          return null
        }
      }

      val targetEntityId = (targetEntity as WorkspaceEntityBase).id
      val targetCurrentState = targetState[targetEntityId]
      when (targetCurrentState) {
        is ReplaceState.NoChange -> return ParentsRef.TargetRef(targetEntityId)
        is ReplaceState.Relabel -> return ParentsRef.TargetRef(targetEntityId)
        ReplaceState.Remove -> return null
        null -> Unit
      }

      when {
        entityFilter(replaceWithRootEntity.entitySource) && entityFilter(targetEntity.entitySource) -> {
          error("This branch should be already processed because we process 'target' entities first")
        }
        entityFilter(replaceWithRootEntity.entitySource) && !entityFilter(targetEntity.entitySource) -> {
          replaceWorkspaceData(targetEntity.id, replaceWithRootEntity.id, null)
          return ParentsRef.TargetRef(targetEntityId)
        }
        !entityFilter(replaceWithRootEntity.entitySource) && entityFilter(targetEntity.entitySource) -> {
          error("This branch should be already processed because we process 'target' entities first")
        }
        !entityFilter(replaceWithRootEntity.entitySource) && !entityFilter(targetEntity.entitySource) -> {
          doNothingOn(targetEntity.id, replaceWithRootEntity.id)
          return ParentsRef.TargetRef(targetEntityId)
        }
      }

      error("Unexpected branch")
    }

    fun findEntityInTargetStorage(replaceWithRootEntity: WorkspaceEntityBase): WorkspaceEntity? {
      return if (replaceWithRootEntity is WorkspaceEntityWithPersistentId) {
        val persistentId = replaceWithRootEntity.persistentId
        targetStorage.resolve(persistentId)
      }
      else {
        targetStorage.entities(replaceWithRootEntity.id.clazz.findWorkspaceEntity())
          .filter {
            targetStorage.entityDataByIdOrDie((it as WorkspaceEntityBase).id) == replaceWithStorage.entityDataByIdOrDie(
              replaceWithRootEntity.id)
          }
          .firstOrNull()
      }
    }
  }

  // This class is just a wrapper to combine functions logically
  private inner class TargetProcessor {
    fun processEntity(targetEntityToReplace: WorkspaceEntity) {
      targetEntityToReplace as WorkspaceEntityBase

      val trackToParents = TrackToParents(targetEntityToReplace.id)
      buildRootTrack(trackToParents, targetStorage)
      findSameEntity(trackToParents)
    }


    private fun findSameEntity(targetEntityTrack: TrackToParents): EntityId? {
      val parentsAssociation = targetEntityTrack.parents.associateWith { findSameEntity(it) }

      val targetEntityState = targetState[targetEntityTrack.entity]
      if (targetEntityState != null) {
        when (targetEntityState) {
          is ReplaceState.NoChange -> return targetEntityState.replaceWithEntityId
          is ReplaceState.Relabel -> return targetEntityState.replaceWithEntityId
          ReplaceState.Remove -> return null
        }
      }

      val targetEntityData = targetStorage.entityDataByIdOrDie(targetEntityTrack.entity)
      if (targetEntityTrack.parents.isEmpty()) {
        return findAndReplaceRootEntity(targetEntityData.createEntity(targetStorage) as WorkspaceEntityBase)
      }
      else {
        val entriesList = parentsAssociation.entries.toList()

        val targetParents = mutableSetOf<ParentsRef>()
        var index = 0
        var replaceWithEntityData: WorkspaceEntityData<out WorkspaceEntity>? = null
        for (i in entriesList.indices) {
          index = i
          val replaceWithEntityIds = childrenInStorage(entriesList[i].value, targetEntityTrack.entity.clazz, replaceWithStorage)
          val replaceWithChildrenMap = makeEntityDataCollection(replaceWithEntityIds, replaceWithStorage)
          replaceWithEntityData = replaceWithChildrenMap.removeSome(targetEntityData)
          while (replaceWithEntityData != null && replaceWithState[replaceWithEntityData.createEntityId()] != null) {
            replaceWithEntityData = replaceWithChildrenMap.removeSome(targetEntityData)
          }
          if (replaceWithEntityData != null) {
            targetParents += ParentsRef.TargetRef(entriesList[i].key.entity)
            break
          }
        }

        entriesList.drop(index).forEach { tailItem ->
          val replaceWithEntityIds = childrenInStorage(tailItem.value, targetEntityTrack.entity.clazz, replaceWithStorage)
          val replaceWithChildrenMap = makeEntityDataCollection(replaceWithEntityIds, replaceWithStorage)
          var replaceWithMyEntityData = replaceWithChildrenMap.removeSome(targetEntityData)
          while (replaceWithMyEntityData != null && replaceWithEntityData!!.createEntityId() != replaceWithMyEntityData.createEntityId()) {
            replaceWithMyEntityData = replaceWithChildrenMap.removeSome(targetEntityData)
          }
          if (replaceWithMyEntityData != null) {
            targetParents += ParentsRef.TargetRef(tailItem.key.entity)
          }
        }

        if (replaceWithEntityData != null) {
          val replaceWithTrackToParents = TrackToParents(replaceWithEntityData.createEntityId())
          buildRootTrack(replaceWithTrackToParents, replaceWithStorage)
          val alsoTargetParents = replaceWithTrackToParents.parents.map { findSameEntityInTargetStore(it) }
          targetParents.addAll(alsoTargetParents.filterNotNull())
        }

        val targetParentClazzes = targetParents.map {
          when (it) {
            is ParentsRef.AddedElement -> it.replaceWithEntityId.clazz
            is ParentsRef.TargetRef -> it.targetEntityId.clazz
          }
        }
        val requiredParentMissing = targetEntityData.getRequiredParents().any { it.toClassId() !in targetParentClazzes }

        val targetSourceMatches = entityFilter(targetEntityData.entitySource)
        if (replaceWithEntityData == null || requiredParentMissing) {
          when (targetSourceMatches) {
            true -> removeWorkspaceData(targetEntityTrack.entity, null)
            false -> doNothingOn(targetEntityTrack.entity, null)
          }
        }
        else {
          val replaceWithSourceMatches = entityFilter(replaceWithEntityData.entitySource)
          @Suppress("KotlinConstantConditions")
          when {
            targetSourceMatches && replaceWithSourceMatches -> {
              replaceWorkspaceData(targetEntityTrack.entity, replaceWithEntityData.createEntityId(), targetParents)
            }
            targetSourceMatches && !replaceWithSourceMatches -> {
              removeWorkspaceData(targetEntityTrack.entity, replaceWithEntityData.createEntityId())
              return null
            }
            !targetSourceMatches && replaceWithSourceMatches -> {
              replaceWorkspaceData(targetEntityTrack.entity, replaceWithEntityData.createEntityId(), targetParents)
            }
            !targetSourceMatches && !replaceWithSourceMatches -> {
              doNothingOn(targetEntityTrack.entity, replaceWithEntityData.createEntityId())
            }
          }
        }
        return replaceWithEntityData?.createEntityId()
      }
    }


    fun findAndReplaceRootEntity(targetRootEntity: WorkspaceEntityBase): EntityId? {
      val targetRootEntityId = targetRootEntity.id
      val currentTargetState = targetState[targetRootEntityId]
      if (currentTargetState != null) {
        when (currentTargetState) {
          is ReplaceState.NoChange -> {
            return currentTargetState.replaceWithEntityId
          }
          is ReplaceState.Relabel -> {
            return currentTargetState.replaceWithEntityId
          }
          ReplaceState.Remove -> {
            return null
          }
        }
      }

      val replaceWithEntity = findEntityInStorage(targetRootEntity, replaceWithStorage, targetStorage)
      if (replaceWithEntity == null) {
        if (entityFilter(targetRootEntity.entitySource)) {
          targetRootEntityId operation Operation.Remove
          targetRootEntityId.addState(ReplaceState.Remove)
          return null
        }
        else {
          targetRootEntityId.addState(ReplaceState.NoChange(null))
          return null
        }
      }

      replaceWithEntity as WorkspaceEntityBase
      when {
        entityFilter(targetRootEntity.entitySource) && entityFilter(replaceWithEntity.entitySource) -> {
          replaceWorkspaceData(targetRootEntity.id, replaceWithEntity.id, null)
          return replaceWithEntity.id
        }
        entityFilter(targetRootEntity.entitySource) && !entityFilter(replaceWithEntity.entitySource) -> {
          removeWorkspaceData(targetRootEntity.id, replaceWithEntity.id)
          return null
        }
        !entityFilter(targetRootEntity.entitySource) && entityFilter(replaceWithEntity.entitySource) -> {
          replaceWorkspaceData(targetRootEntity.id, replaceWithEntity.id, null)
          return replaceWithEntity.id
        }
        !entityFilter(targetRootEntity.entitySource) && !entityFilter(replaceWithEntity.entitySource) -> {
          doNothingOn(targetRootEntity.id, replaceWithEntity.id)
          return replaceWithEntity.id
        }
      }

      error("Unexpected branch")
    }
  }

  private class EntityDataStrategy : Hash.Strategy<WorkspaceEntityData<out WorkspaceEntity>> {
    override fun equals(a: WorkspaceEntityData<out WorkspaceEntity>?, b: WorkspaceEntityData<out WorkspaceEntity>?): Boolean {
      if (a == null || b == null) {
        return false
      }
      return a.equalsIgnoringEntitySource(b)
    }

    override fun hashCode(o: WorkspaceEntityData<out WorkspaceEntity>?): Int {
      return o?.hashCodeIgnoringEntitySource() ?: 0
    }
  }

  private fun <K, V> Object2ObjectOpenCustomHashMap<K, List<V>>.removeSome(key: K): V? {
    val existingValue = this[key] ?: return null
    return if (existingValue.size == 1) {
      this.remove(key)
      existingValue.single()
    }
    else {
      val firstElement = existingValue[0]
      this[key] = existingValue.drop(1)
      firstElement
    }
  }

  private fun addElementOperation(targetParentEntity: Set<ParentsRef>?, replaceWithEntity: EntityId) {
    addOperations += AddElement(targetParentEntity, replaceWithEntity)
    replaceWithEntity.addState(ReplaceWithState.ElementMoved)
  }

  private fun replaceWorkspaceData(targetEntityId: EntityId, replaceWithEntityId: EntityId, parents: Set<ParentsRef>?) {
    targetEntityId operation Operation.Relabel(replaceWithEntityId, parents)
    targetEntityId.addState(ReplaceState.Relabel(replaceWithEntityId, parents))
    replaceWithEntityId.addState(ReplaceWithState.Relabel(targetEntityId))
  }

  private fun removeWorkspaceData(targetEntityId: EntityId, replaceWithEntityId: EntityId?) {
    targetEntityId operation Operation.Remove
    targetEntityId.addState(ReplaceState.Remove)
    replaceWithEntityId?.addState(ReplaceWithState.NoChangeTraceLost)
  }

  private fun doNothingOn(targetEntityId: EntityId, replaceWithEntityId: EntityId?) {
    targetEntityId.addState(ReplaceState.NoChange(replaceWithEntityId))
    replaceWithEntityId?.addState(ReplaceWithState.NoChange(targetEntityId))
  }

  private fun EntityId.addState(state: ReplaceState) {
    val currentState = targetState[this]
    require(currentState == null) {
      "Unexpected existing state for $this: $currentState"
    }
    targetState[this] = state
  }

  private fun EntityId.addState(state: ReplaceWithState) {
    val currentState = replaceWithState[this]
    require(currentState == null)
    replaceWithState[this] = state
  }

  private infix fun EntityId.operation(state: Operation) {
    val currentState = operations[this]
    require(currentState == null) {
      "Unexpected existing state for ${this.asString()}: $currentState"
    }
    operations[this] = state
  }

  private fun findSameEntityInTargetStore(replaceWithTrack: TrackToParents): ParentsRef? {
    val parentsAssociation = replaceWithTrack.parents.associateWith { findSameEntityInTargetStore(it) }
    val replaceWithEntityData = replaceWithStorage.entityDataByIdOrDie(replaceWithTrack.entity)

    val replaceWithCurrentState = replaceWithState[replaceWithTrack.entity]
    when (replaceWithCurrentState) {
      is ReplaceWithState.NoChange -> return ParentsRef.TargetRef(replaceWithCurrentState.targetEntityId)
      ReplaceWithState.NoChangeTraceLost -> return null
      is ReplaceWithState.Relabel -> return ParentsRef.TargetRef(replaceWithCurrentState.targetEntityId)
      ReplaceWithState.ElementMoved -> return ParentsRef.AddedElement(replaceWithTrack.entity)
      null -> Unit
    }

    if (replaceWithTrack.parents.isEmpty()) {
      val targetRootEntityId = findAndReplaceRootEntityInTargetStore(
        replaceWithEntityData.createEntity(replaceWithStorage) as WorkspaceEntityBase)
      return targetRootEntityId
    }
    else {
      val entriesList = parentsAssociation.entries.toList()

      val targetParents = mutableSetOf<EntityId>()
      var targetEntityData: WorkspaceEntityData<out WorkspaceEntity>? = null
      for (i in entriesList.indices) {
        val value = entriesList[i].value
        if (value is ParentsRef.TargetRef) {
          targetEntityData = findEntityInTargetStore(replaceWithEntityData, value.targetEntityId, replaceWithTrack.entity.clazz)
          if (targetEntityData != null) {
            targetParents += entriesList[i].key.entity
            break
          }
        }
      }
      if (targetEntityData == null) {
        for (entry in entriesList) {
          val value = entry.value
          if (value is ParentsRef.AddedElement) {
            return ParentsRef.AddedElement(replaceWithTrack.entity)
          }
        }
      }
      return targetEntityData?.createEntityId()?.let { ParentsRef.TargetRef(it) }
    }
  }

  private fun findEntityInTargetStore(replaceWithEntityData: WorkspaceEntityData<out WorkspaceEntity>,
                                      targetParentEntityId: EntityId,
                                      childClazz: Int): WorkspaceEntityData<out WorkspaceEntity>? {
    var targetEntityData1: WorkspaceEntityData<out WorkspaceEntity>? = null
    val targetEntityIds = childrenInStorage(targetParentEntityId, childClazz, targetStorage)
    val targetChildrenMap = makeEntityDataCollection(targetEntityIds, targetStorage)
    targetEntityData1 = targetChildrenMap.removeSome(replaceWithEntityData)
    while (targetEntityData1 != null && replaceWithState[targetEntityData1.createEntityId()] != null) {
      targetEntityData1 = targetChildrenMap.removeSome(replaceWithEntityData)
    }
    return targetEntityData1
  }

  fun findAndReplaceRootEntityInTargetStore(replaceWithRootEntity: WorkspaceEntityBase): ParentsRef? {
    val replaceRootEntityId = replaceWithRootEntity.id
    val replaceWithCurrentState = replaceWithState[replaceRootEntityId]
    when (replaceWithCurrentState) {
      is ReplaceWithState.NoChange -> return ParentsRef.TargetRef(replaceWithCurrentState.targetEntityId)
      ReplaceWithState.NoChangeTraceLost -> return null
      is ReplaceWithState.Relabel -> return ParentsRef.TargetRef(replaceWithCurrentState.targetEntityId)
      ReplaceWithState.ElementMoved -> TODO()
      null -> Unit
    }

    val targetEntity = findEntityInStorage(replaceWithRootEntity, targetStorage, replaceWithStorage)
    if (targetEntity == null) {
      if (entityFilter(replaceWithRootEntity.entitySource)) {
        addSubtree(null, replaceRootEntityId)
        return ParentsRef.AddedElement(replaceRootEntityId)
      }
      else {
        replaceRootEntityId.addState(ReplaceWithState.NoChangeTraceLost)
        return null
      }
    }

    targetEntity as WorkspaceEntityBase
    when {
      !entityFilter(targetEntity.entitySource) && entityFilter(replaceWithRootEntity.entitySource) -> {
        replaceWorkspaceData(targetEntity.id, replaceRootEntityId, null)
        return ParentsRef.TargetRef(targetEntity.id)
      }
      !entityFilter(targetEntity.entitySource) && !entityFilter(replaceWithRootEntity.entitySource) -> {
        doNothingOn(targetEntity.id, replaceRootEntityId)
        return ParentsRef.TargetRef(targetEntity.id)
      }
      else -> error("Unexpected branch")
    }
  }

  private fun addSubtree(parents: Set<ParentsRef>?, replaceWithEntityId: EntityId) {
    val currentState = replaceWithState[replaceWithEntityId]
    when (currentState) {
      ReplaceWithState.ElementMoved -> return
      is ReplaceWithState.NoChange -> error("Unexpected state")
      ReplaceWithState.NoChangeTraceLost -> error("Unexpected state")
      is ReplaceWithState.Relabel -> error("Unexpected state")
      null -> Unit
    }

    addElementOperation(parents, replaceWithEntityId)

    replaceWithStorage.refs.getChildrenRefsOfParentBy(replaceWithEntityId.asParent()).values.flatten().forEach {
      val replaceWithChildEntityData = replaceWithStorage.entityDataByIdOrDie(it.id)
      if (!entityFilter(replaceWithChildEntityData.entitySource)) return@forEach
      val trackToParents = TrackToParents(it.id)
      buildRootTrack(trackToParents, replaceWithStorage)
      val sameEntity = findSameEntityInTargetStore(trackToParents)
      if (sameEntity is ParentsRef.TargetRef) {
        return@forEach
      }
      val otherParents = trackToParents.parents.mapNotNull { findSameEntityInTargetStore(it) }
      addSubtree((otherParents + ParentsRef.AddedElement(replaceWithEntityId)).toSet(), it.id)
    }
  }


  private fun makeEntityDataCollection(targetChildEntityIds: List<ChildEntityId>,
                                       storage: AbstractEntityStorage): Object2ObjectOpenCustomHashMap<WorkspaceEntityData<out WorkspaceEntity>, List<WorkspaceEntityData<out WorkspaceEntity>>> {
    val targetChildrenMap = Object2ObjectOpenCustomHashMap<WorkspaceEntityData<out WorkspaceEntity>, List<WorkspaceEntityData<out WorkspaceEntity>>>(
      EntityDataStrategy())
    targetChildEntityIds.forEach { id ->
      val value = storage.entityDataByIdOrDie(id.id)
      val existingValue = targetChildrenMap[value]
      targetChildrenMap[value] = if (existingValue != null) existingValue + value else listOf(value)
    }
    return targetChildrenMap
  }

  companion object {
    private fun buildRootTrack(entity: TrackToParents,
                               storage: AbstractEntityStorage) {
      val parents = storage.refs.getParentRefsOfChild(entity.entity.asChild())
      parents.values.forEach { parentEntityId ->
        val parentTrack = TrackToParents(parentEntityId.id)
        buildRootTrack(parentTrack, storage)
        entity.parents += parentTrack
        parentTrack.child = entity
      }
    }

    private fun childrenInStorage(entityId: EntityId?, childrenClass: Int, storage: AbstractEntityStorage): List<ChildEntityId> {
      val targetEntityIds = if (entityId != null) {
        val targetChildren = storage.refs.getChildrenRefsOfParentBy(entityId.asParent())

        val targetFoundChildren = targetChildren.filterKeys {
          sameClass(it.childClass, childrenClass, it.connectionType)
        }
        require(targetFoundChildren.size < 2) { "Got unexpected amount of children" }

        if (targetFoundChildren.isEmpty()) {
          emptyList()
        }
        else {
          val (_, targetChildEntityIds) = targetFoundChildren.entries.single()
          targetChildEntityIds
        }
      }
      else {
        emptyList()
      }
      return targetEntityIds
    }

    private fun findEntityInStorage(rootEntity: WorkspaceEntityBase,
                                    goalStorage: AbstractEntityStorage,
                                    oppositeStorage: AbstractEntityStorage): WorkspaceEntity? {
      return if (rootEntity is WorkspaceEntityWithPersistentId) {
        val persistentId = rootEntity.persistentId
        goalStorage.resolve(persistentId)
      }
      else {
        goalStorage.entities(rootEntity.id.clazz.findWorkspaceEntity())
          .filter {
            goalStorage.entityDataByIdOrDie((it as WorkspaceEntityBase).id) == oppositeStorage.entityDataByIdOrDie(rootEntity.id)
          }
          .firstOrNull()
      }
    }

    // I DON'T KNOW WHY KOTLIN SHUFFLE DOESN'T WORK, I JUST DON'T UNDERSTAND WHY
    private fun <T> MutableList<T>.shuffleHard(rng: Random): MutableList<T> {
      for (index in 0 until this.size) {
        val randomIndex = rng.nextInt(index + 1)

        // Swap with the random position
        val temp = this[index]
        this[index] = this[randomIndex]
        this[randomIndex] = temp
      }

      return this
    }

  }
}

internal sealed interface Operation {
  object Remove : Operation
  class Relabel(val replaceWithEntityId: EntityId, val parents: Set<ParentsRef>?) : Operation
}

internal data class AddElement(val parents: Set<ParentsRef>?, val replaceWithSource: EntityId)

internal sealed interface ReplaceState {
  data class Relabel(val replaceWithEntityId: EntityId, val parents: Set<ParentsRef>? = null) : ReplaceState
  data class NoChange(val replaceWithEntityId: EntityId?) : ReplaceState
  object Remove : ReplaceState
}

internal sealed interface ReplaceWithState {
  object ElementMoved : ReplaceWithState
  data class NoChange(val targetEntityId: EntityId) : ReplaceWithState
  data class Relabel(val targetEntityId: EntityId) : ReplaceWithState
  object NoChangeTraceLost : ReplaceWithState
}

sealed interface ParentsRef {
  data class TargetRef(val targetEntityId: EntityId) : ParentsRef
  data class AddedElement(val replaceWithEntityId: EntityId) : ParentsRef
}

class TrackToParents(
  val entity: EntityId,
  var child: TrackToParents? = null,
  val parents: MutableList<TrackToParents> = ArrayList(),
) {
  fun singleRoot(): TrackToParents {
    if (parents.isEmpty()) return this
    return parents.single().singleRoot()
  }
}
