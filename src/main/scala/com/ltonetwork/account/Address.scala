package com.ltonetwork.account

import com.ltonetwork.crypto
import com.ltonetwork.state.ByteStr
import com.ltonetwork.transaction.ValidationError
import com.ltonetwork.transaction.ValidationError.InvalidAddress
import com.ltonetwork.utils.{Base58, ScorexLogging, base58Length}
import play.api.libs.json._

import java.nio.ByteBuffer

sealed trait Address {
  val bytes: ByteStr
  lazy val address: String    = bytes.base58
  lazy val stringRepr: String = address

  override def toString: String = stringRepr

  override def equals(obj: scala.Any): Boolean = obj match {
    case a: Address => bytes == a.bytes
    case _          => false
  }

  override def hashCode(): Int = java.util.Arrays.hashCode(bytes.arr)
}

object Address extends ScorexLogging {

  val Prefix: String = "address:"

  val AddressVersion: Byte     = 1
  val ChecksumLength: Int      = 4
  val HashLength: Int          = 20
  val AddressLength: Int       = 1 + 1 + HashLength + ChecksumLength
  val AddressStringLength: Int = base58Length(AddressLength)

  private def scheme = AddressScheme.current

  private class AddressImpl(val bytes: ByteStr) extends Address

  def fromPublicKey(publicKey: Array[Byte], chainId: Byte = scheme.chainId): Address = {
    val publicKeyHash   = crypto.secureHash(publicKey)
    val withoutChecksum = ByteBuffer.allocate(1 + 1 + HashLength).put(AddressVersion).put(chainId).put(publicKeyHash, 0, HashLength).array()
    val bytes           = ByteBuffer.allocate(AddressLength).put(withoutChecksum).put(crypto.secureHash(withoutChecksum), 0, ChecksumLength).array()
    new AddressImpl(ByteStr(bytes))
  }

  def fromBytes(addressBytes: Array[Byte], chainId: Byte = scheme.chainId): Either[InvalidAddress, Address] = {
    val version = addressBytes.head
    val network = addressBytes.tail.head
    (for {
      _ <- Either.cond(version == AddressVersion, (), s"Unknown address version: $version")
      _ <- Either.cond(network == chainId, (), s"Data from other network: expected: $chainId(${chainId.toChar}), actual: $network(${network.toChar})")
      _ <- Either.cond(addressBytes.length == Address.AddressLength,
                       (),
                       s"Wrong addressBytes length: expected: ${Address.AddressLength}, actual: ${addressBytes.length}")
      checkSum          = addressBytes.takeRight(ChecksumLength)
      checkSumGenerated = calcCheckSum(addressBytes.dropRight(ChecksumLength))
      _ <- Either.cond(checkSum.sameElements(checkSumGenerated), (), s"Bad address checksum")
    } yield new AddressImpl(ByteStr(addressBytes))).left.map(InvalidAddress)
  }

  def fromBytes(bytes: Array[Byte], position: Int): Either[InvalidAddress, (Address, Int)] = {
    bytes(position) match {
      case Address.AddressVersion =>
        val addressEnd   = position + Address.AddressLength
        val addressBytes = bytes.slice(position, addressEnd)
        Address.fromBytes(addressBytes).map((_, addressEnd))
      case _ => Left(ValidationError.InvalidAddress("Unknown address version"))
    }
  }

  def fromString(addressStr: String): Either[ValidationError, Address] = {
    val base58String = if (addressStr.startsWith(Prefix)) addressStr.drop(Prefix.length) else addressStr
    for {
      _ <- Either.cond(base58String.length <= AddressStringLength,
                       (),
                       InvalidAddress(s"Wrong address string length: max=$AddressStringLength, actual: ${base58String.length}"))
      byteArray <- Base58.decode(base58String).toEither.left.map(ex => InvalidAddress(s"Unable to decode base58: ${ex.getMessage}"))
      address   <- fromBytes(byteArray)
    } yield address
  }

  private def calcCheckSum(withoutChecksum: Array[Byte]): Array[Byte] = crypto.secureHash(withoutChecksum).take(ChecksumLength)

  implicit val jsonFormat: Format[Address] = Format[Address](
    Reads(jsValue => fromString(jsValue.as[String]).fold(err => JsError(err.toString), JsSuccess(_))),
    Writes(addr => JsString(addr.stringRepr))
  )
}
