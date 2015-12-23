package shade.memcached

import java.io._

import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
 * Represents a type class that needs to be implemented
 * for serialization/deserialization to work.
 */
trait Codec[T] {
  def serialize(value: T): Array[Byte]
  def deserialize(data: Array[Byte]): T
}

object Codec extends BaseCodecs

trait BaseCodecs {
  implicit object IntBinaryCodec extends Codec[Int] {
    override def serialize(value: Int): Array[Byte] =
      Array(
        (value >>> 24).asInstanceOf[Byte],
        (value >>> 16).asInstanceOf[Byte],
        (value >>> 8).asInstanceOf[Byte],
        value.asInstanceOf[Byte]
      )

    override def deserialize(data: Array[Byte]): Int =
      (data(0).asInstanceOf[Int] & 255) << 24 |
        (data(1).asInstanceOf[Int] & 255) << 16 |
        (data(2).asInstanceOf[Int] & 255) << 8 |
        data(3).asInstanceOf[Int] & 255
  }

  implicit object LongBinaryCodec extends Codec[Long] {
    override def serialize(value: Long): Array[Byte] =
      Array(
        (value >>> 56).asInstanceOf[Byte],
        (value >>> 48).asInstanceOf[Byte],
        (value >>> 40).asInstanceOf[Byte],
        (value >>> 32).asInstanceOf[Byte],
        (value >>> 24).asInstanceOf[Byte],
        (value >>> 16).asInstanceOf[Byte],
        (value >>> 8).asInstanceOf[Byte],
        value.asInstanceOf[Byte]
      )

    override def deserialize(data: Array[Byte]): Long =
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
    override def serialize(value: Boolean): Array[Byte] =
      Array((if (value) 1 else 0).asInstanceOf[Byte])

    override def deserialize(data: Array[Byte]): Boolean =
      data.isDefinedAt(0) && data(0) == 1
  }

  implicit object CharBinaryCodec extends Codec[Char] {
    override def serialize(value: Char): Array[Byte] = Array(
      (value >>> 8).asInstanceOf[Byte],
      value.asInstanceOf[Byte]
    )

    override def deserialize(data: Array[Byte]): Char =
      ((data(0).asInstanceOf[Int] & 255) << 8 |
        data(1).asInstanceOf[Int] & 255)
        .asInstanceOf[Char]
  }

  implicit object ShortBinaryCodec extends Codec[Short] {
    override def serialize(value: Short): Array[Byte] = Array(
      (value >>> 8).asInstanceOf[Byte],
      value.asInstanceOf[Byte]
    )

    override def deserialize(data: Array[Byte]): Short =
      ((data(0).asInstanceOf[Short] & 255) << 8 |
        data(1).asInstanceOf[Short] & 255)
        .asInstanceOf[Short]
  }

  implicit object StringBinaryCodec extends Codec[String] {
    override def serialize(value: String): Array[Byte] = value.getBytes("UTF-8")
    override def deserialize(data: Array[Byte]): String = new String(data, "UTF-8")
  }

  implicit object ArrayByteBinaryCodec extends Codec[Array[Byte]] {
    override def serialize(value: Array[Byte]): Array[Byte] = value
    override def deserialize(data: Array[Byte]): Array[Byte] = data
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

    override def serialize(value: S): Array[Byte] =
      using (new ByteArrayOutputStream()) { buf =>
        using (new ObjectOutputStream(buf)) { out =>
          out.writeObject(value)
          out.close()
          buf.toByteArray
        }
      }

    override def deserialize(data: Array[Byte]): S =
      using (new ByteArrayInputStream(data)) { buf =>
        val in = new GenericCodecObjectInputStream(classTag, buf)
        using (in) { inp =>
          inp.readObject().asInstanceOf[S]
        }
      }
  }

  implicit def AnyRefBinaryCodec[S <: Serializable](implicit ev: ClassTag[S]): Codec[S] =
    new GenericCodec[S](ev)

}

trait MemcachedCodecs extends BaseCodecs with GenericCodec

object MemcachedCodecs extends MemcachedCodecs

