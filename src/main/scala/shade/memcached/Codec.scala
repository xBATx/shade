/*
 * Copyright (c) 2012-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://github.com/monix/shade
 *
 * Licensed under the MIT License (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 * https://github.com/monix/shade/blob/master/LICENSE.txt
 */

package shade.memcached

import java.io._

import scala.annotation.implicitNotFound
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
 * Represents a type class that needs to be implemented
 * for serialization/deserialization to work.
 */
@implicitNotFound("Could not find any Codec implementation for type ${T}. Please provide one or import shade.memcached.MemcachedCodecs._")
trait Codec[T] {
  def serialize(value: T): Array[Byte]
  def deserialize(data: Array[Byte]): T
}

object Codec extends BaseCodecs

trait BaseCodecs {
  implicit object IntBinaryCodec extends Codec[Int] {
    def serialize(value: Int): Array[Byte] =
      Array(
        (value >>> 24).asInstanceOf[Byte],
        (value >>> 16).asInstanceOf[Byte],
        (value >>> 8).asInstanceOf[Byte],
        value.asInstanceOf[Byte])

    def deserialize(data: Array[Byte]): Int =
      (data(0).asInstanceOf[Int] & 255) << 24 |
        (data(1).asInstanceOf[Int] & 255) << 16 |
        (data(2).asInstanceOf[Int] & 255) << 8 |
        data(3).asInstanceOf[Int] & 255
  }

  implicit object DoubleBinaryCodec extends Codec[Double] {
    import java.lang.{ Double => JvmDouble }
    def serialize(value: Double): Array[Byte] = {
      val l = JvmDouble.doubleToLongBits(value)
      LongBinaryCodec.serialize(l)
    }

    def deserialize(data: Array[Byte]): Double = {
      val l = LongBinaryCodec.deserialize(data)
      JvmDouble.longBitsToDouble(l)
    }
  }

  implicit object FloatBinaryCodec extends Codec[Float] {
    import java.lang.{ Float => JvmFloat }
    def serialize(value: Float): Array[Byte] = {
      val i = JvmFloat.floatToIntBits(value)
      IntBinaryCodec.serialize(i)
    }

    def deserialize(data: Array[Byte]): Float = {
      val i = IntBinaryCodec.deserialize(data)
      JvmFloat.intBitsToFloat(i)
    }
  }

  implicit object LongBinaryCodec extends Codec[Long] {
    def serialize(value: Long): Array[Byte] =
      Array(
        (value >>> 56).asInstanceOf[Byte],
        (value >>> 48).asInstanceOf[Byte],
        (value >>> 40).asInstanceOf[Byte],
        (value >>> 32).asInstanceOf[Byte],
        (value >>> 24).asInstanceOf[Byte],
        (value >>> 16).asInstanceOf[Byte],
        (value >>> 8).asInstanceOf[Byte],
        value.asInstanceOf[Byte])

    def deserialize(data: Array[Byte]): Long =
      (data(0).asInstanceOf[Long] & 255) << 56 |
        (data(1).asInstanceOf[Long] & 255) << 48 |
        (data(2).asInstanceOf[Long] & 255) << 40 |
        (data(3).asInstanceOf[Long] & 255) << 32 |
        (data(4).asInstanceOf[Long] & 255) << 24 |
        (data(5).asInstanceOf[Long] & 255) << 16 |
        (data(6).asInstanceOf[Long] & 255) << 8 |
        data(7).asInstanceOf[Long] & 255
  }

  implicit object BooleanBinaryCodec extends Codec[Boolean] {
    def serialize(value: Boolean): Array[Byte] =
      Array((if (value) 1 else 0).asInstanceOf[Byte])

    def deserialize(data: Array[Byte]): Boolean =
      data.isDefinedAt(0) && data(0) == 1
  }

  implicit object CharBinaryCodec extends Codec[Char] {
    def serialize(value: Char): Array[Byte] = Array(
      (value >>> 8).asInstanceOf[Byte],
      value.asInstanceOf[Byte])

    def deserialize(data: Array[Byte]): Char =
      ((data(0).asInstanceOf[Int] & 255) << 8 |
        data(1).asInstanceOf[Int] & 255)
        .asInstanceOf[Char]
  }

  implicit object ShortBinaryCodec extends Codec[Short] {
    def serialize(value: Short): Array[Byte] = Array(
      (value >>> 8).asInstanceOf[Byte],
      value.asInstanceOf[Byte])

    def deserialize(data: Array[Byte]): Short =
      ((data(0).asInstanceOf[Short] & 255) << 8 |
        data(1).asInstanceOf[Short] & 255)
        .asInstanceOf[Short]
  }

  implicit object StringBinaryCodec extends Codec[String] {
    def serialize(value: String): Array[Byte] = value.getBytes("UTF-8")
    def deserialize(data: Array[Byte]): String = new String(data, "UTF-8")
  }

  implicit object ArrayByteBinaryCodec extends Codec[Array[Byte]] {
    def serialize(value: Array[Byte]): Array[Byte] = value
    def deserialize(data: Array[Byte]): Array[Byte] = data
  }
}

trait GenericCodec {

  private[this] class GenericCodec[S <: Serializable](classTag: ClassTag[S]) extends Codec[S] {

    def using[T <: Closeable, R](obj: T)(f: T => R): R =
      try
        f(obj)
      finally
        try obj.close() catch {
          case NonFatal(_) => // does nothing
        }

    def serialize(value: S): Array[Byte] =
      using(new ByteArrayOutputStream()) { buf =>
        using(new ObjectOutputStream(buf)) { out =>
          out.writeObject(value)
          out.close()
          buf.toByteArray
        }
      }

    def deserialize(data: Array[Byte]): S =
      using(new ByteArrayInputStream(data)) { buf =>
        val in = new GenericCodecObjectInputStream(classTag, buf)
        using(in) { inp =>
          inp.readObject().asInstanceOf[S]
        }
      }
  }

  implicit def AnyRefBinaryCodec[S <: Serializable](implicit ev: ClassTag[S]): Codec[S] =
    new GenericCodec[S](ev)

}

trait MemcachedCodecs extends BaseCodecs with GenericCodec

object MemcachedCodecs extends MemcachedCodecs

