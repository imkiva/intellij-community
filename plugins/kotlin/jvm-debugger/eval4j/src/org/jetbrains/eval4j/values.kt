// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.eval4j

import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.LabelNode

interface Value : org.jetbrains.org.objectweb.asm.tree.analysis.Value {
    val asmType: Type
    val valid: Boolean
    override fun getSize(): Int = asmType.size

    override fun toString(): String
}

@Suppress("ClassName")
object NOT_A_VALUE : Value {
    override val asmType: Type = Type.getObjectType("<invalid>")
    override val valid = false
    override fun getSize(): Int = 1

    override fun toString() = "NOT_A_VALUE"
}

@Suppress("ClassName")
object VOID_VALUE : Value {
    override val asmType: Type = Type.VOID_TYPE
    override val valid: Boolean = false
    override fun toString() = "VOID_VALUE"
}

fun makeNotInitializedValue(t: Type): Value? {
    return when (t.sort) {
        Type.VOID -> null
        else -> NotInitialized(t)
    }
}

class NotInitialized(override val asmType: Type) : Value {
    override val valid = false
    override fun toString() = "NotInitialized: $asmType"
}

abstract class AbstractValueBase<out V>(
    override val asmType: Type
) : Value {
    override val valid = true
    abstract val value: V

    override fun toString() = "$value: $asmType"

    override fun equals(other: Any?): Boolean {
        if (other !is AbstractValue<*>) return false

        return value == other.value && asmType == other.asmType
    }

    override fun hashCode(): Int {
        return value!!.hashCode() + 17 * asmType.hashCode()
    }
}

abstract class AbstractValue<V>(override val value: V, asmType: Type) : AbstractValueBase<V>(asmType)

class IntValue(value: Int, asmType: Type) : AbstractValue<Int>(value, asmType)
class LongValue(value: Long) : AbstractValue<Long>(value, Type.LONG_TYPE)
class FloatValue(value: Float) : AbstractValue<Float>(value, Type.FLOAT_TYPE)
class DoubleValue(value: Double) : AbstractValue<Double>(value, Type.DOUBLE_TYPE)
open class ObjectValue(value: Any?, asmType: Type) : AbstractValue<Any?>(value, asmType)

class NewObjectValue(asmType: Type) : ObjectValue(null, asmType) {
    override var value: Any? = null
        get(): Any? {
            return field ?: throw IllegalStateException("Trying to access an uninitialized object: $this")
        }
}

class LabelValue(value: LabelNode) : AbstractValue<LabelNode>(value, Type.VOID_TYPE)

fun boolean(v: Boolean) = IntValue(if (v) 1 else 0, Type.BOOLEAN_TYPE)
fun byte(v: Byte) = IntValue(v.toInt(), Type.BYTE_TYPE)
fun short(v: Short) = IntValue(v.toInt(), Type.SHORT_TYPE)
fun char(v: Char) = IntValue(v.toInt(), Type.CHAR_TYPE)
fun int(v: Int) = IntValue(v, Type.INT_TYPE)
fun long(v: Long) = LongValue(v)
fun float(v: Float) = FloatValue(v)
fun double(v: Double) = DoubleValue(v)

val NULL_VALUE = ObjectValue(null, Type.getObjectType("null"))

val Value.boolean: Boolean get() = (this as IntValue).value == 1
val Value.int: Int get() = (this as IntValue).value
val Value.long: Long get() = (this as LongValue).value
val Value.float: Float get() = (this as FloatValue).value
val Value.double: Double get() = (this as DoubleValue).value

fun Value.obj(expectedType: Type = asmType): Any? {
    return when (expectedType) {
        Type.BOOLEAN_TYPE -> this.boolean
        Type.SHORT_TYPE -> (this as IntValue).int.toShort()
        Type.BYTE_TYPE -> (this as IntValue).int.toByte()
        Type.CHAR_TYPE -> (this as IntValue).int.toChar()
        else -> (this as AbstractValue<*>).value
    }
}

fun <T : Any> T?.checkNull(): T {
    if (this == null) {
        throwInterpretingException(NullPointerException())
    }
    return this
}

fun throwInterpretingException(e: Throwable): Nothing {
    throw Eval4JInterpretingException(e)
}

fun throwBrokenCodeException(e: Throwable): Nothing {
    throw BrokenCode(e)
}