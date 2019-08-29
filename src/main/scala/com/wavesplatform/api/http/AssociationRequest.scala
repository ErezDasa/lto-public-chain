package com.wavesplatform.api.http

import cats.implicits._
import com.wavesplatform.account.{Address, PublicKeyAccount}
import com.wavesplatform.transaction.{AssociationTransaction, Proofs, ValidationError}
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import play.api.libs.json.Json

object AssociationRequest {
  implicit val unsignedDataRequestReads = Json.reads[AssociationRequest]
  implicit val signedDataRequestReads   = Json.reads[SignedAssociationRequest]
}

case class AssociationRequest(version: Byte,
                              sender: String,
                              party: String,
                              associationType: Int,
                              hash: Option[String],
                              fee: Long,
                              timestamp: Option[Long] = None)

@ApiModel(value = "Signed Data transaction")
case class SignedAssociationRequest(@ApiModelProperty(required = true)
                                    version: Byte,
                                    @ApiModelProperty(value = "Base58 encoded sender public key", required = true)
                                    senderPublicKey: String,
                                    @ApiModelProperty(value = "Counterparty address", required = true)
                                    party: String,
                                    @ApiModelProperty(value = "Association type", required = true)
                                    associationType: Int,
                                    @ApiModelProperty(value = "Association data hash ", required = false)
                                    hash: Option[String],
                                    @ApiModelProperty(required = true)
                                    fee: Long,
                                    @ApiModelProperty(required = true)
                                    timestamp: Long,
                                    @ApiModelProperty(required = true)
                                    proofs: List[String])
    extends BroadcastRequest {
  def toTx: Either[ValidationError, AssociationTransaction] =
    for {
      _sender     <- PublicKeyAccount.fromBase58String(senderPublicKey)
      _party      <- Address.fromString(party)
      _proofBytes <- proofs.traverse(s => parseBase58(s, "invalid proof", Proofs.MaxProofStringSize))
      _hash <- hash.map(h => parseBase58(h, "Incorrect hash", AssociationTransaction.HashLength)) match {
        case None           => Right(None)
        case Some(Right(x)) => Right(Some(x))
        case Some(Left(y))  => Left(y)
      }
      _proofs <- Proofs.create(_proofBytes)
      t       <- AssociationTransaction.create(version, _sender, _party, associationType, _hash, fee, timestamp, _proofs)
    } yield t
}
