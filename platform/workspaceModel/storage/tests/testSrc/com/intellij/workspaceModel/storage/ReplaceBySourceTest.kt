// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspaceModel.storage

import com.intellij.testFramework.UsefulTestCase.assertEmpty
import com.intellij.testFramework.UsefulTestCase.assertOneElement
import com.intellij.workspaceModel.storage.entities.test.addChildEntity
import com.intellij.workspaceModel.storage.entities.test.addParentEntity
import com.intellij.workspaceModel.storage.entities.test.addSampleEntity
import com.intellij.workspaceModel.storage.entities.test.api.*
import com.intellij.workspaceModel.storage.impl.ReplaceBySourceAsGraph
import com.intellij.workspaceModel.storage.impl.MutableEntityStorageImpl
import com.intellij.workspaceModel.storage.impl.assertConsistency
import com.intellij.workspaceModel.storage.impl.exceptions.ReplaceBySourceException
import org.hamcrest.CoreMatchers.isA
import com.intellij.workspaceModel.storage.entities.test.api.modifyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException


/**
 * Replace by source test plan
 *
 * Plus sign means implemented
 *
 *   + Same entity modification
 *   + Add entity in remote builder
 *   + Remove entity in remote builder
 *   + Parent + child - modify parent
 *   + Parent + child - modify child
 *   + Parent + child - remove child
 *   + Parent + child - remove parent
 * - Parent + child - wrong source for parent nullable
 * - Parent + child - wrong source for parent not null
 * - Parent + child - wrong source for child nullable
 * - Parent + child - wrong source for child not null
 *   + Parent + child - wrong source for parent in remote builder
 *   + Parent + child - wrong source for child in remote builder
 *
 * Soft links
 *   + Change property of persistent id
 * - Change property of persistent id. Entity with link should be in wrong source
 * - Change property of persistent id. Entity with persistent id should be in wrong source (what should happen?)
 *
 *
 * different connection types
 * persistent id
 */
class ReplaceBySourceTest {

  private lateinit var builder: MutableEntityStorageImpl

  @JvmField
  @Rule
  val expectedException = ExpectedException.none()

  @Before
  fun setUp() {
    builder = createEmptyBuilder()
  }

  @Test
  fun `add entity`() {
    Assume.assumeFalse(builder.useNewRbs)

    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    val replacement = createEmptyBuilder()
    replacement.addSampleEntity("hello1", SampleEntitySource("1"))
    builder.replaceBySource({ it == SampleEntitySource("1") }, replacement)
    assertEquals(setOf("hello1", "hello2"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `remove entity`() {
    Assume.assumeFalse(builder.useNewRbs)

    val source1 = SampleEntitySource("1")
    builder.addSampleEntity("hello1", source1)
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    builder.replaceBySource({ it == source1 }, createEmptyBuilder())
    assertEquals("hello2", builder.singleSampleEntity().stringProperty)
    builder.assertConsistency()
  }

  @Test
  fun `remove and add entity`() {
    Assume.assumeFalse(builder.useNewRbs)

    val source1 = SampleEntitySource("1")
    builder.addSampleEntity("hello1", source1)
    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    val replacement = createEmptyBuilder()
    replacement.addSampleEntity("updated", source1)
    builder.replaceBySource({ it == source1 }, replacement)
    assertEquals(setOf("hello2", "updated"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `multiple sources`() {
    Assume.assumeFalse(builder.useNewRbs)

    val sourceA1 = SampleEntitySource("a1")
    val sourceA2 = SampleEntitySource("a2")
    val sourceB = SampleEntitySource("b")
    builder.addSampleEntity("a", sourceA1)
    builder.addSampleEntity("b", sourceB)
    val replacement = createEmptyBuilder()
    replacement.addSampleEntity("new", sourceA2)
    builder.replaceBySource({ it is SampleEntitySource && it.name.startsWith("a") }, replacement)
    assertEquals(setOf("b", "new"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `work with different entity sources`() {
    Assume.assumeFalse(builder.useNewRbs)

    val sourceA1 = SampleEntitySource("a1")
    val sourceA2 = SampleEntitySource("a2")
    val parentEntity = builder.addParentEntity(source = sourceA1)
    val replacement = createBuilderFrom(builder)
    replacement.addChildEntity(parentEntity = parentEntity, source = sourceA2)
    builder.replaceBySource({ it == sourceA2 }, replacement)
    assertEquals(1, builder.toSnapshot().entities(XParentEntity::class.java).toList().size)
    assertEquals(1, builder.toSnapshot().entities(XChildEntity::class.java).toList().size)
    builder.assertConsistency()
  }

  @Test
  fun `empty storages`() {
    Assume.assumeFalse(builder.useNewRbs)

    val builder2 = createEmptyBuilder()

    builder.replaceBySource({ true }, builder2)
    assertTrue(builder.collectChanges(
      createEmptyBuilder()).isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `replace with empty storage`() {
    Assume.assumeFalse(builder.useNewRbs)

    builder.addSampleEntity("data1")
    builder.addSampleEntity("data2")
    resetChanges()
    val originalStorage = builder.toSnapshot()

    builder.replaceBySource({ true }, createEmptyBuilder())
    val collectChanges = builder.collectChanges(originalStorage)
    assertEquals(1, collectChanges.size)
    assertEquals(2, collectChanges.values.single().size)
    assertTrue(collectChanges.values.single().all { it is EntityChange.Removed<*> })
    builder.assertConsistency()
    assertTrue(builder.entities(SampleEntity::class.java).toList().isEmpty())
  }

  @Test
  fun `add entity with false source`() {
    Assume.assumeFalse(builder.useNewRbs)

    builder.addSampleEntity("hello2", SampleEntitySource("2"))
    resetChanges()
    val replacement = createEmptyBuilder()
    replacement.addSampleEntity("hello1", SampleEntitySource("1"))
    builder.replaceBySource({ false }, replacement)
    assertEquals(setOf("hello2"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    assertTrue(builder.collectChanges(
      createEmptyBuilder()).isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `entity modification`() {
    Assume.assumeFalse(builder.useNewRbs)

    val entity = builder.addSampleEntity("hello2")
    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(entity) {
      stringProperty = "Hello Alex"
    }
    builder.replaceBySource({ true }, replacement)
    assertEquals(setOf("Hello Alex"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `adding entity in builder`() {
    Assume.assumeFalse(builder.useNewRbs)

    val replacement = createBuilderFrom(builder)
    replacement.addSampleEntity("myEntity")
    builder.replaceBySource({ true }, replacement)
    assertEquals(setOf("myEntity"), builder.entities(SampleEntity::class.java).mapTo(HashSet()) { it.stringProperty })
    builder.assertConsistency()
  }

  @Test
  fun `removing entity in builder`() {
    Assume.assumeFalse(builder.useNewRbs)

    val entity = builder.addSampleEntity("myEntity")
    val replacement = createBuilderFrom(builder)
    replacement.removeEntity(entity)
    builder.replaceBySource({ true }, replacement)
    assertTrue(builder.entities(SampleEntity::class.java).toList().isEmpty())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - modify parent`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity("myProperty")
    builder.addChildEntity(parent, "myChild")

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(parent) {
      parentProperty = "newProperty"
    }

    builder.replaceBySource({ true }, replacement)

    val child = assertOneElement(builder.entities(XChildEntity::class.java).toList())
    assertEquals("newProperty", child.parentEntity.parentProperty)
    assertOneElement(builder.entities(XParentEntity::class.java).toList())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - modify child`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(child) {
      childProperty = "newProperty"
    }

    builder.replaceBySource({ true }, replacement)

    val updatedChild = assertOneElement(builder.entities(XChildEntity::class.java).toList())
    assertEquals("newProperty", updatedChild.childProperty)
    assertEquals(updatedChild, assertOneElement(builder.entities(XParentEntity::class.java).toList()).children.single())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - remove parent`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = createBuilderFrom(builder)
    replacement.removeEntity(parent)

    builder.replaceBySource({ true }, replacement)

    assertEmpty(builder.entities(XChildEntity::class.java).toList())
    assertEmpty(builder.entities(XParentEntity::class.java).toList())
    builder.assertConsistency()
  }

  @Test
  fun `child and parent - change parent for child`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity("myProperty")
    val parent2 = builder.addParentEntity("anotherProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(child) {
      this.parentEntity = parent2
    }

    builder.replaceBySource({ true }, replacement)

    builder.assertConsistency()
    val parents = builder.entities(XParentEntity::class.java).toList()
    assertTrue(parents.single { it.parentProperty == "myProperty" }.children.none())
    assertEquals(child, parents.single { it.parentProperty == "anotherProperty" }.children.single())
  }

  @Test
  fun `child and parent - change parent for child - 2`() {
    Assume.assumeFalse(builder.useNewRbs)

    expectedException.expectCause(isA(ReplaceBySourceException::class.java))

    val parent = builder.addParentEntity("myProperty", source = AnotherSource)
    val parent2 = builder.addParentEntity("anotherProperty", source = MySource)
    val child = builder.addChildEntity(parent2, "myChild", source = AnotherSource)

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(child) {
      this.parentEntity = parent
    }

    builder.replaceBySource({ it is MySource }, replacement)
  }

  @Test
  fun `child and parent - change parent for child - 3`() {
    Assume.assumeFalse(builder.useNewRbs)

    // Difference with the test above: different initial parent
    val parent = builder.addParentEntity("myProperty", source = AnotherSource)
    val parent2 = builder.addParentEntity("anotherProperty", source = MySource)
    val child = builder.addChildEntity(parent, "myChild", source = AnotherSource)

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(child) {
      this.parentEntity = parent2
    }

    builder.replaceBySource({ it is MySource }, replacement)

    builder.assertConsistency()
    val parents = builder.entities(XParentEntity::class.java).toList()
    assertTrue(parents.single { it.parentProperty == "anotherProperty" }.children.none())
    assertEquals(child, parents.single { it.parentProperty == "myProperty" }.children.single())
  }

  @Test
  fun `child and parent - remove child`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity("myProperty")
    val child = builder.addChildEntity(parent, "myChild")

    val replacement = createBuilderFrom(builder)
    replacement.removeEntity(child)

    builder.replaceBySource({ true }, replacement)

    assertEmpty(builder.entities(XChildEntity::class.java).toList())
    assertOneElement(builder.entities(XParentEntity::class.java).toList())
    assertEmpty(builder.entities(XParentEntity::class.java).single().children.toList())
    builder.assertConsistency()
  }

  @Test
  fun `fail - child and parent - different source for parent`() {
    Assume.assumeFalse(builder.useNewRbs)

    expectedException.expectCause(isA(ReplaceBySourceException::class.java))

    val replacement = createBuilderFrom(builder)
    val parent = replacement.addParentEntity("myProperty", source = AnotherSource)
    val child = replacement.addChildEntity(parent, "myChild")

    builder.replaceBySource({ it is MySource }, replacement)
  }

  @Test
  fun `child and parent - two children of different sources`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity(source = AnotherSource)
    builder.addChildEntity(parent, "MySourceChild", source = MySource)
    val replacement = createBuilderFrom(builder)
    replacement.addChildEntity(parent, "AnotherSourceChild", source = AnotherSource)

    builder.replaceBySource({ it is MySource }, replacement)

    builder.assertConsistency()
    assertOneElement(builder.entities(XParentEntity::class.java).toList())
    val child = assertOneElement(builder.entities(XChildEntity::class.java).toList())
    assertEquals("MySourceChild", child.childProperty)
  }

  @Test
  fun `child and parent - trying to remove parent and leave child`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addParentEntity("prop", AnotherSource)
    builder.addChildEntity(parentEntity)
    val replacement = createBuilderFrom(builder)
    replacement.removeEntity(parentEntity)

    builder.replaceBySource({ it is AnotherSource }, replacement)
    builder.assertConsistency()

    assertEmpty(builder.entities(XParentEntity::class.java).toList())
    assertEmpty(builder.entities(XChildEntity::class.java).toList())
  }

  @Test
  fun `child and parent - different source for child`() {
    Assume.assumeFalse(builder.useNewRbs)

    val replacement = createBuilderFrom(builder)
    val parent = replacement.addParentEntity("myProperty")
    val child = replacement.addChildEntity(parent, "myChild", source = AnotherSource)

    builder.replaceBySource({ it is MySource }, replacement)

    assertEmpty(builder.entities(XChildEntity::class.java).toList())
    assertOneElement(builder.entities(XParentEntity::class.java).toList())
    assertEmpty(builder.entities(XParentEntity::class.java).single().children.toList())
    builder.assertConsistency()
  }

  @Test
  fun `remove child of different source`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parent = builder.addParentEntity(source = AnotherSource)
    val child = builder.addChildEntity(parent, source = MySource)

    val replacement = createBuilderFrom(builder)
    replacement.removeEntity(child)

    builder.replaceBySource({ it is MySource }, replacement)
  }

  @Test
  fun `entity with soft reference`() {
    Assume.assumeFalse(builder.useNewRbs)

    val named = builder.addNamedEntity("MyName")
    val linked = builder.addWithSoftLinkEntity(named.persistentId)
    resetChanges()
    builder.assertConsistency()

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(named) {
      this.myName = "NewName"
    }

    replacement.assertConsistency()

    builder.replaceBySource({ true }, replacement)
    assertEquals("NewName", assertOneElement(builder.entities(NamedEntity::class.java).toList()).myName)
    assertEquals("NewName", assertOneElement(builder.entities(WithSoftLinkEntity::class.java).toList()).link.presentableName)

    builder.assertConsistency()
  }

  @Test
  fun `entity with soft reference remove reference`() {
    Assume.assumeFalse(builder.useNewRbs)

    val named = builder.addNamedEntity("MyName")
    val linked = builder.addWithListSoftLinksEntity("name", listOf(named.persistentId))
    resetChanges()
    builder.assertConsistency()

    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(linked) {
      this.links = mutableListOf()
    }

    replacement.assertConsistency()

    builder.replaceBySource({ true }, replacement)

    builder.assertConsistency()
  }

  @Test
  fun `replace by source with composite id`() {
    Assume.assumeFalse(builder.useNewRbs)

    val replacement = createEmptyBuilder()
    val namedEntity = replacement.addNamedEntity("MyName")
    val composedEntity = replacement.addComposedIdSoftRefEntity("AnotherName", namedEntity.persistentId)
    replacement.addComposedLinkEntity(composedEntity.persistentId)

    replacement.assertConsistency()
    builder.replaceBySource({ true }, replacement)
    builder.assertConsistency()

    assertOneElement(builder.entities(NamedEntity::class.java).toList())
    assertOneElement(builder.entities(ComposedIdSoftRefEntity::class.java).toList())
    assertOneElement(builder.entities(ComposedLinkEntity::class.java).toList())
  }

  /*
    Not sure if this should work this way. We can track persistentId changes in builder, but what if we perform replaceBySource with storage?
    @Test
    fun `entity with soft reference - linked has wrong source`() {
    Assume.assumeFalse(builder.useNewRbs)

      val builder = PEntityStorageBuilder.create()
      val named = builder.addNamedEntity("MyName")
      val linked = builder.addWithSoftLinkEntity(named.persistentId, AnotherSource)
      resetChanges()
      builder.assertConsistency()

      val replacement = PEntityStorageBuilder.from(builder)
      replacement.modifyEntity(ModifiableNamedEntity::class.java, named) {
        this.name = "NewName"
      }

      replacement.assertConsistency()

      builder.replaceBySource({ it is MySource }, replacement)
      assertEquals("NewName", assertOneElement(builder.entities(NamedEntity::class.java).toList()).name)
      assertEquals("NewName", assertOneElement(builder.entities(WithSoftLinkEntity::class.java).toList()).link.presentableName)

      builder.assertConsistency()
    }
  */

  @Ignore("Not supported yet")
  @Test
  fun `trying to create two similar persistent ids`() {
    Assume.assumeFalse(builder.useNewRbs)

    val namedEntity = builder.addNamedEntity("MyName", source = AnotherSource)
    val replacement = createBuilderFrom(builder)
    replacement.modifyEntity(namedEntity) {
      this.myName = "AnotherName"
    }

    replacement.addNamedEntity("MyName", source = MySource)

    builder.replaceBySource({ it is MySource }, replacement)
  }

  @Test
  fun `changing parent`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addParentEntity()
    val childEntity = builder.addChildEntity(parentEntity, source = AnotherSource)

    val replacement = createBuilderFrom(builder)

    val anotherParent = replacement.addParentEntity("Another")
    replacement.modifyEntity(childEntity) {
      this.parentEntity = anotherParent
    }

    builder.replaceBySource({ it is MySource }, replacement)
  }

  @Test
  fun `replace same entity with persistent id and different sources`() {
    Assume.assumeFalse(builder.useNewRbs)

    val name = "Hello"
    builder.addNamedEntity(name, source = MySource)
    val replacement = createEmptyBuilder()
    replacement.addNamedEntity(name, source = AnotherSource)

    builder.replaceBySource({ it is AnotherSource }, replacement)

    assertEquals(1, builder.entities(NamedEntity::class.java).toList().size)
    assertEquals(AnotherSource, builder.entities(NamedEntity::class.java).single().entitySource)
  }

  @Test
  fun `replace dummy parent entity by real entity`() {
    Assume.assumeFalse(builder.useNewRbs)

    val namedParent = builder.addNamedEntity("name", "foo", MyDummyParentSource)
    builder.addNamedChildEntity(namedParent, "fooChild", AnotherSource)

    val replacement = createEmptyBuilder()
    replacement.addNamedEntity("name", "bar", MySource)
    builder.replaceBySource({ it is MySource || it is MyDummyParentSource }, replacement)

    assertEquals("bar", builder.entities(NamedEntity::class.java).single().additionalProperty)
    val child = builder.entities(NamedChildEntity::class.java).single()
    assertEquals("fooChild", child.childProperty)
    assertEquals("bar", child.parentEntity.additionalProperty)
  }

  @Test
  fun `do not replace real parent entity by dummy entity`() {
    Assume.assumeFalse(builder.useNewRbs)

    val namedParent = builder.addNamedEntity("name", "foo", MySource)
    builder.addNamedChildEntity(namedParent, "fooChild", AnotherSource)

    val replacement = createEmptyBuilder()
    replacement.addNamedEntity("name", "bar", MyDummyParentSource)
    builder.replaceBySource({ it is MySource || it is MyDummyParentSource }, replacement)

    assertEquals("foo", builder.entities(NamedEntity::class.java).single().additionalProperty)
    val child = builder.entities(NamedChildEntity::class.java).single()
    assertEquals("fooChild", child.childProperty)
    assertEquals("foo", child.parentEntity.additionalProperty)
  }

  @Test
  fun `do not replace real parent entity by dummy entity but replace children`() {
    Assume.assumeFalse(builder.useNewRbs)

    val namedParent = builder.addNamedEntity("name", "foo", MySource)
    builder.addNamedChildEntity(namedParent, "fooChild", MySource)

    val replacement = createEmptyBuilder()
    val newParent = replacement.addNamedEntity("name", "bar", MyDummyParentSource)
    replacement.addNamedChildEntity(newParent, "barChild", MySource)
    builder.replaceBySource({ it is MySource || it is MyDummyParentSource }, replacement)

    assertEquals("foo", builder.entities(NamedEntity::class.java).single().additionalProperty)
    val child = builder.entities(NamedChildEntity::class.java).single()
    assertEquals("barChild", child.childProperty)
    assertEquals("foo", child.parentEntity.additionalProperty)
  }

  @Test
  fun `replace parents with completely different children`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addNamedEntity("PrimaryParent", source = AnotherSource)
    builder.addNamedChildEntity(parentEntity, "PrimaryChild", source = MySource)
    builder.addNamedEntity("SecondaryParent", source = AnotherSource)

    val replacement = createEmptyBuilder()
    replacement.addNamedEntity("PrimaryParent", source = AnotherSource)
    val anotherParentEntity = replacement.addNamedEntity("SecondaryParent2", source = AnotherSource)
    replacement.addNamedChildEntity(anotherParentEntity, source = MySource)

    builder.replaceBySource({ it is AnotherSource }, replacement)

    val primaryChild = builder.entities(NamedChildEntity::class.java).find { it.childProperty == "PrimaryChild" }!!
    assertEquals("PrimaryParent", primaryChild.parentEntity.myName)
  }

  @Test
  @Ignore("Well, this case is explicetely not supported yet")
  fun `replace oneToOne connection with partial move`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addOoParentEntity()
    builder.addOoChildEntity(parentEntity)

    val replacement = createEmptyBuilder()
    val anotherParent = replacement.addOoParentEntity(source = AnotherSource)
    replacement.addOoChildEntity(anotherParent)

    builder.replaceBySource({ it is MySource }, replacement)

    builder.assertConsistency()
  }

  @Test
  fun `replace oneToOne connection with partial move and pid`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addOoParentWithPidEntity(source = AnotherSource)
    builder.addOoChildForParentWithPidEntity(parentEntity, source = MySource)

    val replacement = createEmptyBuilder()
    val anotherParent = replacement.addOoParentWithPidEntity(source = MySource)
    replacement.addOoChildForParentWithPidEntity(anotherParent, source = MySource)

    builder.replaceBySource({ it is MySource }, replacement)

    builder.assertConsistency()
  }

  @Test
  fun `replace oneToOne connection with partial move and pid directly via replacer`() {
    Assume.assumeFalse(builder.useNewRbs)

    val parentEntity = builder.addOoParentWithPidEntity(source = AnotherSource)
    builder.addOoChildForParentWithPidEntity(parentEntity, source = MySource)

    val replacement = createEmptyBuilder()
    val anotherParent = replacement.addOoParentWithPidEntity(source = MySource)
    replacement.addOoChildForParentWithPidEntity(anotherParent, source = MySource)

    ReplaceBySourceAsGraph().replaceBySourceAsGraph(builder, replacement, {it is MySource }, true)

    builder.assertConsistency()
  }

  private fun resetChanges() {
    builder = builder.toSnapshot().toBuilder() as MutableEntityStorageImpl
  }
}
