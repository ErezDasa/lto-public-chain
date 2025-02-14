package com.ltonetwork.api

import cats.Applicative
import com.ltonetwork.account.{PrivateKeyAccount, PublicKeyAccount}
import com.ltonetwork.crypto.{digestLength, signatureLength}
import com.ltonetwork.state.ByteStr
import com.ltonetwork.transaction.ValidationError._
import com.ltonetwork.transaction.{Proofs, Transaction, TransactionBuilder, ValidationError}
import com.ltonetwork.utils.base58Length
import play.api.libs.json._
import supertagged.TaggedType

package object requests {
  import cats.instances.list._
  import cats.syntax.either._
  import cats.syntax.traverse._

  val SignatureStringLength: Int = base58Length(signatureLength)
  val DigestStringLength: Int    = base58Length(digestLength)

  def parseBase58(v: String, error: String, maxLength: Int): Validation[ByteStr] =
    if (v.length > maxLength) Left(GenericError(error))
    else ByteStr.decodeBase58(v).toOption.toRight(GenericError(error))

  def parseBase58(v: Option[String], error: String, maxLength: Int): Validation[ByteStr] =
    v.fold[Either[ValidationError, ByteStr]](Right(ByteStr(Array.emptyByteArray)))(_v => parseBase58(_v, error, maxLength))

  def parseBase58ToOption(v: Option[String], error: String, maxLength: Int): Validation[Option[ByteStr]] =
    v.fold[Either[ValidationError, Option[ByteStr]]](Right(None)) { s =>
      parseBase58(s, error, maxLength).map(b => Option(b))
    }

  def toProofs(maybeSignature: Option[ByteStr], maybeProofs: Option[Proofs]): Validation[Proofs] =
    (maybeSignature, maybeProofs) match {
      case (Some(sig), Some(proofs)) if proofs.nonEmpty && proofs.head != sig =>
        Left(GenericError("Both proofs and signature are provided, but proofs do not match signature"))
      case _ =>
        maybeProofs
          .orElse(maybeSignature.map(s => Proofs(List(s))))
          .fold[Either[ValidationError, Proofs]](Proofs.empty.asRight)(p => Proofs.create(p))
    }

  implicit val jsResultApplicative: Applicative[JsResult] = new Applicative[JsResult] {
    override def pure[A](x: A): JsResult[A] = JsSuccess(x)

    override def ap[A, B](ff: JsResult[A => B])(fa: JsResult[A]): JsResult[B] = (ff, fa) match {
      case (JsSuccess(f, _), JsSuccess(a, _)) => JsSuccess(f(a))
      case (JsError(e1), JsError(e2))         => JsError(JsError.merge(e1, e2))
      case (JsError(e), _)                    => JsError(e)
      case (_, JsError(e))                    => JsError(e)
    }
  }

  implicit val proofsReads: Reads[Proofs] = Reads {
    case JsArray(values) =>
      values.toList
        .traverse {
          case JsString(v) =>
            JsSuccess(v).flatMap(s => ByteStr.decodeBase58(s).fold(e => JsError(JsonValidationError("invalid.base58", e.getMessage)), JsSuccess(_)))
          case _ => JsError("expected.string")
        }
        .flatMap(Proofs.create(_) match {
          case Right(value) => JsSuccess(value)
          case Left(err)    => JsError(JsonValidationError("invalid.proofs", err.toString))
        })
    case JsNull => JsSuccess(Proofs.empty)
    case _      => JsError("invalid.proofs")
  }

  implicit val proofsWrites: Writes[Proofs] = Writes { proofs =>
    JsArray(proofs.map(s => JsString(s.toString)))
  }

  object ProofStr extends TaggedType[String]
  type ProofStr = ProofStr.Type

  implicit object ProofStrReads extends Reads[ProofStr] {
    override def reads(json: JsValue): JsResult[ProofStr] = json match {
      case JsNull      => JsSuccess(ProofStr(""))
      case JsString(s) => JsSuccess(ProofStr(s))
      case _           => JsError(Seq(JsPath -> Seq(JsonValidationError("error.expected.jsstring"))))
    }
  }

  implicit class TransactionVersion[T <: TransactionBuilder](val builder: T) extends AnyVal {
    def latestVersion: Byte = builder.supportedVersions.last
  }

  private[requests] def defaultTimestamp = 0L

  implicit class TransactionSignOps[T <: Transaction](val tx: T) extends AnyVal {
    def signMaybe(signer: Option[PublicKeyAccount])(implicit sign: (T, PrivateKeyAccount) => T): T = signer match {
      case Some(account: PrivateKeyAccount) => sign(tx, account)
      case _ => tx
    }
    def signMaybe(signer: PublicKeyAccount)(implicit sign: (T, PrivateKeyAccount) => T): T = signMaybe(Some(signer))
  }
}
