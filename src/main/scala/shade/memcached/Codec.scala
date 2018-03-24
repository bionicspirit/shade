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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, Closeable, ObjectOutputStream}

import monix.execution.misc.NonFatal
import net.spy.memcached.CachedData
import net.spy.memcached.transcoders._

import scala.annotation.implicitNotFound
import net.spy.memcached.CachedData.MAX_SIZE

import scala.reflect.ClassTag

/**
  * Represents a type class that needs to be implemented
  * for serialization/deserialization to work.
  */
@implicitNotFound("Could not find any Codec implementation for type ${T}.")
trait Codec[T] extends Transcoder[T] {
  /**
    * Returns `true` if the decoding needs to happen asynchronously,
    * or `false` otherwise.
    *
    * Decoding should be marked for asynchrony in case it is
    * expensive, for example when compression is applied.
    */
  def asyncDecode(d: CachedData): Boolean

  /**
    * Encode the given value to a byte array with flags attached,
    * meant for storage by the Memcached client.
    */
  def encode(value: T): CachedData

  /**
    * Decodes byte arrays with flags, as retrieved by the Memcached client,
    * into the value it represents.
    */
  def decode(data: CachedData): T

  /** Get the maximum size of objects handled by this codec. */
  def getMaxSize: Int
}

object Codec extends DefaultCodecs

trait DefaultCodecs extends DefaultCodecsLevel0 {

  import java.lang.{Float => JvmFloat, Double => JvmDouble}

  /** Default codec for `Short`. */
  implicit object ShortBinaryCodec extends GenericIntCodec[Short](
    flags = 2 << 8, // SerializingTranscoder.SPECIAL_INT
    toInt = (v: Short) => v.toInt,
    fromInt = (v: Int) => v.toShort
  )

  /** Default codec for `Char`. */
  implicit object CharBinaryCodec extends GenericIntCodec[Char](
    flags = 2 << 8, // SerializingTranscoder.SPECIAL_INT
    toInt = (v: Char) => v.toInt,
    fromInt = (v: Int) => v.toChar
  )

  /** Default codec for `Int`. */
  implicit object IntBinaryCodec extends GenericIntCodec[Int](
    flags = 2 << 8, // SerializingTranscoder.SPECIAL_INT
    toInt = (v: Int) => v,
    fromInt = (v: Int) => v
  )

  /** Default codec for `Long`. */
  implicit object LongBinaryCodec extends GenericLongCodec[Long](
    flags = 3 << 8, // SerializingTranscoder.SPECIAL_LONG
    toLong = (v: Long) => v,
    fromLong = (v: Long) => v
  )

  /** Default codec for `Float`. */
  implicit object FloatBinaryCodec extends GenericIntCodec[Float](
    flags = 6 << 8, // SerializingTranscoder.SPECIAL_FLOAT
    toInt = JvmFloat.floatToRawIntBits,
    fromInt = JvmFloat.intBitsToFloat
  )

  /** Default codec for `Double`. */
  implicit object DoubleBinaryCodec extends GenericLongCodec[Double](
    flags = 7 << 8, // SerializingTranscoder.SPECIAL_DOUBLE
    toLong = JvmDouble.doubleToRawLongBits,
    fromLong = JvmDouble.longBitsToDouble
  )

  /** Default codec for `Byte`. */
  implicit object ByteBinaryCodec extends Codec[Byte] {
    final val FLAGS = 5 << 8 // SerializingTranscoder.SPECIAL_BYTE

    def asyncDecode(d: CachedData): Boolean = false

    def encode(value: Byte): CachedData = {
      val bytes = packedUtils.encodeByte(value)
      new CachedData(FLAGS, bytes, getMaxSize)
    }

    def decode(data: CachedData): Byte =
      data.getData match {
        case null => 0
        case bytes =>
          packedUtils.decodeByte(bytes)
      }

    def getMaxSize: Int =
      MAX_SIZE
  }

  /** Default codec for `Boolean`. */
  implicit object BooleanCodec extends Codec[Boolean] {
    // SerializingTranscoder.SPECIAL_BOOLEAN
    final val FLAGS = 1 << 8

    def asyncDecode(d: CachedData): Boolean = false

    def encode(value: Boolean): CachedData = {
      val bytes = packedUtils.encodeBoolean(value)
      new CachedData(FLAGS, bytes, getMaxSize)
    }

    def decode(data: CachedData): Boolean =
      data.getData match {
        case null => false
        case bytes =>
          packedUtils.decodeBoolean(bytes)
      }

    def getMaxSize: Int =
      MAX_SIZE
  }


  implicit def ArrayBinaryCodec: Codec[Array[Byte]] = new Codec[Array[Byte]] {
    private[this] val tc = new SerializingTranscoder()

    def asyncDecode(d: CachedData): Boolean = tc.asyncDecode(d)

    def encode(value: Array[Byte]): CachedData = tc.encode(value)

    def decode(data: CachedData): Array[Byte] = tc.decode(data).asInstanceOf[Array[Byte]]

    def getMaxSize: Int = tc.getMaxSize
  }

  implicit def StringBinaryCodec: Codec[String] = new Codec[String] {
    private[this] val tc = new SerializingTranscoder()
    def asyncDecode(d: CachedData): Boolean = tc.asyncDecode(d)

    def encode(value: String): CachedData = tc.encode(value)

    def decode(data: CachedData): String = tc.decode(data).asInstanceOf[String]

    def getMaxSize: Int = tc.getMaxSize
  }


}

private[memcached] trait DefaultCodecsLevel0 {

  private[this] class GenericCodec[S <: Serializable](classTag: ClassTag[S]) extends Codec[S] {

    private[this] val tc = new SerializingTranscoder()
    def asyncDecode(d: CachedData): Boolean =
      tc.asyncDecode(d)

    def encode(value: S): CachedData = {
      if (value == null) throw new NullPointerException("Null values not supported!")
      tc.encode(serialize(value))
    }

    def decode(data: CachedData): S =
      tc.decode(data) match {
        case value: Array[Byte] => deserialize(value)
        case _ => throw new NullPointerException("Null values not supported!")
      }

    def getMaxSize: Int =
      tc.getMaxSize

    private def using[T <: Closeable, R](obj: T)(f: T => R): R =
      try
        f(obj)
      finally
        try obj.close() catch {
          case NonFatal(_) => // does nothing
        }

    private def serialize(value: S): Array[Byte] =
      using(new ByteArrayOutputStream()) { buf =>
        using(new ObjectOutputStream(buf)) { out =>
          out.writeObject(value)
          out.close()
          buf.toByteArray
        }
      }

    private def deserialize(data: Array[Byte]): S =
      using(new ByteArrayInputStream(data)) { buf =>
        val in = new GenericCodecObjectInputStream(classTag, buf)
        using(in) { inp =>
          inp.readObject().asInstanceOf[S]
        }
      }
  }

  implicit def serializingCodec[S <: Serializable](implicit ev: ClassTag[S]): Codec[S] =
    new GenericCodec[S](ev)


  /** Helper for building codecs that serialize/deserialize to and from `Long`. */
  class GenericLongCodec[A](flags: Int, toLong: A => Long, fromLong: Long => A) extends Codec[A] {
    final val FLAGS = flags

    final def asyncDecode(d: CachedData): Boolean =
      false

    final def encode(value: A): CachedData = {
      val bytes = packedUtils.encodeLong(toLong(value))
      new CachedData(FLAGS, bytes, MAX_SIZE)
    }

    final def decode(data: CachedData): A =
      fromLong(data.getData match {
        case null => 0
        case bytes =>
          packedUtils.decodeLong(bytes)
      })

    final def getMaxSize: Int =
      MAX_SIZE
  }

  /** Helper for building codecs that serialize/deserialize to and from `Int`. */
  class GenericIntCodec[A](flags: Int, toInt: A => Int, fromInt: Int => A) extends Codec[A] {
    final val FLAGS = flags

    final def asyncDecode(d: CachedData): Boolean =
      false

    final def encode(value: A): CachedData = {
      val bytes = packedUtils.encodeInt(toInt(value))
      new CachedData(FLAGS, bytes, MAX_SIZE)
    }

    final def decode(data: CachedData): A =
      fromInt(data.getData match {
        case null => 0
        case bytes =>
          packedUtils.decodeInt(bytes)
      })

    final def getMaxSize: Int =
      MAX_SIZE
  }

  protected final val packedUtils =
    new TranscoderUtils(true)

}