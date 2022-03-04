package com.ltonetwork

import com.ltonetwork.api.requests._
import com.ltonetwork.state._
import com.ltonetwork.transaction.transfer._
import com.ltonetwork.utils.Base58
import org.scalacheck.Gen.{alphaNumChar, choose, listOfN, oneOf}
import org.scalacheck.{Arbitrary, Gen => G}
import org.scalatest.Suite
import scorex.crypto.signatures.Curve25519._

trait RequestGen extends TransactionGen { _: Suite =>
  val nonPositiveLong: G[Long] = choose(Long.MinValue, 0).label("non-positive value")
  val longAttachment: G[String] =
    genBoundedBytes(TransferTransaction.MaxAttachmentSize + 10, TransferTransaction.MaxAttachmentSize + 50)
      .map(Base58.encode)
  val invalidBase58: G[String] = listOfN(50, oneOf(alphaNumChar, oneOf('O', '0', 'l')))
    .map(_.mkString)
    .label("invalid base58")
  val invalidName: G[String] = oneOf(
    genBoundedString(0, 4 - 1),
    genBoundedString(140 + 1, 1000 + 50)
  ).map(new String(_))

  val addressValGen: G[String] = listOfN(32, Arbitrary.arbByte.arbitrary).map(b => Base58.encode(b.toArray))
  val signatureGen: G[ByteStr] = listOfN(SignatureLength, Arbitrary.arbByte.arbitrary)
    .map(b => ByteStr(b.toArray))

  private val commonFields = for {
    _account <- accountGen
    _fee     <- smallFeeGen
  } yield (_account, _fee)

  val transferReq: G[TransferRequest] = for {
    (account, fee) <- commonFields
    recipient      <- addressValGen
    amount         <- positiveLongGen
    attachment     <- genBoundedString(1, 20).map(b => Some(ByteStr(b)))
  } yield TransferRequest(
    version = Some(1),
    fee = fee,
    senderKeyType = Some(account.keyType.reference),
    senderPublicKey = Some(Base58.encode(account.publicKey)),
    recipient = recipient,
    amount = amount,
    attachment = attachment
  )

  val broadcastTransferReq: G[TransferRequest] = for {
    _signature <- signatureGen
    _timestamp <- ntpTimestampGen
    _tr        <- transferReq
  } yield
    TransferRequest(
      version = Some(1),
      timestamp = Some(_timestamp),
      senderKeyType = _tr.senderKeyType,
      senderPublicKey = _tr.senderPublicKey,
      fee = _tr.fee,
      recipient = _tr.recipient,
      amount = _tr.amount,
      attachment = _tr.attachment,
      signature = Some(_signature)
    )

  val leaseReq: G[LeaseRequest] = for {
    _signature <- signatureGen
    _timestamp <- ntpTimestampGen
    _lease     <- leaseGen
  } yield
    LeaseRequest(
      version = Some(1),
      timestamp = Some(_timestamp),
      senderKeyType = Some(_lease.sender.keyType.reference),
      senderPublicKey = Some(Base58.encode(_lease.sender.publicKey)),
      fee = _lease.fee,
      recipient = _lease.recipient.toString,
      amount = _lease.amount,
      signature = Some(_signature)
    )

  val leaseCancelReq: G[CancelLeaseRequest] = for {
    _signature <- signatureGen
    _cancel    <- cancelLeaseGen
  } yield
    CancelLeaseRequest(
      version = Some(1),
      timestamp = Some(_cancel.timestamp),
      senderKeyType = Some(_cancel.sender.keyType.reference),
      senderPublicKey = Some(Base58.encode(_cancel.sender.publicKey)),
      fee = _cancel.fee,
      leaseId = _cancel.leaseId,
      signature = Some(_signature)
    )
}
