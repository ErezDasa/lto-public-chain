package com.ltonetwork.api

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.ltonetwork.lang.ExprEvaluator.Log
import com.ltonetwork.lang.v1.evaluator.ctx.LazyVal
import com.ltonetwork.state.diffs.TransactionDiffer.TransactionValidationError
import com.ltonetwork.transaction.{Transaction, ValidationError}
import play.api.libs.json._

case class ApiErrorResponse(error: Int, message: String)

object ApiErrorResponse {
  implicit val toFormat: Reads[ApiErrorResponse] = Json.reads[ApiErrorResponse]
}

trait ApiError {
  val id: Int
  val message: String
  val code: StatusCode

  lazy val json = Json.obj("error" -> id, "message" -> message)
}

object ApiError {
  def fromValidationError(e: ValidationError): ApiError = {
    e match {
      case ValidationError.InvalidAddress(_)       => InvalidAddress
      case ValidationError.NegativeAmount(x, of)   => NegativeAmount(s"$x of $of")
      case ValidationError.NegativeMinFee(x, of)   => NegativeMinFee(s"$x per $of")
      case ValidationError.InsufficientFee(x)      => InsufficientFee(x)
      case ValidationError.InvalidName             => InvalidName
      case ValidationError.InvalidSignature(_, _)  => InvalidSignature
      case ValidationError.InvalidRequestSignature => InvalidSignature
      case ValidationError.TooBigArray             => TooBigArrayAllocation
      case ValidationError.OverflowError           => OverflowError
      case ValidationError.ToSelf                  => ToSelfError
      case ValidationError.MissingSenderPrivateKey => MissingSenderPrivateKey
      case ValidationError.AccountBasedWallet      => AccountBasedWallet
      case ValidationError.GenericError(ge)        => CustomValidationError(ge)
      case ValidationError.AlreadyInTheState(tx, txHeight) =>
        CustomValidationError(s"Transaction $tx is already in the state on a height of $txHeight")
      case ValidationError.AccountBalanceError(errs)  => CustomValidationError(errs.values.mkString(", "))
      case ValidationError.UnsupportedTransactionType => CustomValidationError("UnsupportedTransactionType")
      case ValidationError.Mistiming(err)             => Mistiming(err)
      case TransactionValidationError(error, tx) =>
        error match {
          case ValidationError.Mistiming(errorMessage) => Mistiming(errorMessage)
          case ValidationError.TransactionNotAllowedByScript(vars, scriptSrc, isTokenScript) =>
            TransactionNotAllowedByScript(tx, vars, scriptSrc, isTokenScript)
          case ValidationError.ScriptExecutionError(err, src, vars, isToken) =>
            ScriptExecutionError(tx, err, src, vars, isToken)
          case _ => StateCheckFailed(tx, fromValidationError(error).message)
        }
      case error => CustomValidationError(error.toString)
    }
  }

  implicit val lvWrites: Writes[LazyVal] = Writes { lv =>
    lv.value.value.attempt
      .map({
        case Left(thr) =>
          Json.obj(
            "status" -> "Failed",
            "error"  -> thr.getMessage
          )
        case Right(Left(err)) =>
          Json.obj(
            "status" -> "Failed",
            "error"  -> err
          )
        case Right(Right(lv)) =>
          Json.obj(
            "status" -> "Success",
            "value"  -> lv.toString
          )
      })()
  }
}

case object Unknown extends ApiError {
  override val id      = 0
  override val code    = StatusCodes.InternalServerError
  override val message = "Error is unknown"
}

case class WrongJson(cause: Option[Throwable] = None, errors: Seq[(JsPath, Seq[JsonValidationError])] = Seq.empty) extends ApiError {
  override val id           = 1
  override val code         = StatusCodes.BadRequest
  override lazy val message = "failed to parse json message"
  override lazy val json: JsObject = Json.obj(
    "error"            -> id,
    "message"          -> message,
    "cause"            -> cause.map(_.toString),
    "validationErrors" -> JsError.toJson(errors)
  )
}

//API Auth
case object ApiKeyNotValid extends ApiError {
  override val id              = 2
  override val code            = StatusCodes.Forbidden
  override val message: String = "Provided API key is not correct"
}

case object DiscontinuedApi extends ApiError {
  override val id      = 3
  override val code    = StatusCodes.BadRequest
  override val message = "This API is no longer supported"
}

case object TooBigArrayAllocation extends ApiError {
  override val id: Int          = 10
  override val message: String  = "Too big sequences requested"
  override val code: StatusCode = StatusCodes.BadRequest
}

//VALIDATION
case object InvalidSignature extends ApiError {
  override val id      = 101
  override val code    = StatusCodes.BadRequest
  override val message = "invalid signature"
}

case object InvalidAddress extends ApiError {
  override val id      = 102
  override val code    = StatusCodes.BadRequest
  override val message = "invalid address"
}

case object InvalidSeed extends ApiError {
  override val id      = 103
  override val code    = StatusCodes.BadRequest
  override val message = "invalid seed"
}

case object InvalidAmount extends ApiError {
  override val id      = 104
  override val code    = StatusCodes.BadRequest
  override val message = "invalid amount"
}

case object InvalidFee extends ApiError {
  override val id      = 105
  override val code    = StatusCodes.BadRequest
  override val message = "invalid fee"
}

case object InvalidSender extends ApiError {
  override val id      = 106
  override val code    = StatusCodes.BadRequest
  override val message = "invalid sender"
}

case object InvalidRecipient extends ApiError {
  override val id      = 107
  override val code    = StatusCodes.BadRequest
  override val message = "invalid recipient"
}

case object InvalidPublicKey extends ApiError {
  override val id      = 108
  override val code    = StatusCodes.BadRequest
  override val message = "invalid public key"
}

case object InvalidNotNumber extends ApiError {
  override val id      = 109
  override val code    = StatusCodes.BadRequest
  override val message = "argument is not a number"
}

case object InvalidMessage extends ApiError {
  override val id      = 110
  override val code    = StatusCodes.BadRequest
  override val message = "invalid message"
}

case object InvalidName extends ApiError {
  override val id: Int          = 111
  override val message: String  = "invalid name"
  override val code: StatusCode = StatusCodes.BadRequest
}

case class StateCheckFailed(tx: Transaction, err: String) extends ApiError {
  override val id: Int          = 112
  override val message: String  = s"State check failed. Reason: $err"
  override val code: StatusCode = StatusCodes.BadRequest
  override lazy val json        = Json.obj("error" -> id, "message" -> message, "tx" -> tx.json())
}

case object OverflowError extends ApiError {
  override val id: Int          = 113
  override val message: String  = "overflow error"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object ToSelfError extends ApiError {
  override val id: Int          = 114
  override val message: String  = "Transaction to yourself"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object MissingSenderPrivateKey extends ApiError {
  override val id: Int          = 115
  override val message: String  = "no private key for sender address in wallet"
  override val code: StatusCode = StatusCodes.BadRequest
}

case object AccountBasedWallet extends ApiError {
  override val id: Int          = 116
  override val message: String  = "The wallet is account-based, can't add/remove any accounts"
  override val code: StatusCode = StatusCodes.BadRequest
}

case class CustomValidationError(errorMessage: String) extends ApiError {
  override val id: Int          = 199
  override val message: String  = errorMessage
  override val code: StatusCode = StatusCodes.BadRequest
}

case object BlockDoesNotExist extends ApiError {
  override val id: Int         = 301
  override val code            = StatusCodes.NotFound
  override val message: String = "block does not exist"
}

case class Mistiming(errorMessage: String) extends ApiError {
  override val id: Int          = Mistiming.Id
  override val message: String  = errorMessage
  override val code: StatusCode = StatusCodes.BadRequest
}

object Mistiming {
  val Id = 303
}

case object DataKeyNotExists extends ApiError {
  override val id: Int         = 304
  override val code            = StatusCodes.NotFound
  override val message: String = "no data for this key"
}

case class ScriptCompilerError(errorMessage: String) extends ApiError {
  override val id: Int          = 305
  override val code: StatusCode = StatusCodes.BadRequest
  override val message: String  = errorMessage
}
case class ScriptExecutionError(tx: Transaction, error: String, scriptSrc: String, log: Log, isTokenScript: Boolean) extends ApiError {
  override val id: Int             = 306
  override val code: StatusCode    = StatusCodes.BadRequest
  override val message: String     = s"Error while executing ${if (isTokenScript) "token" else "account"}-script: $error"
  override lazy val json: JsObject = ScriptErrorJson(id, tx, message, scriptSrc, log)

}

case class TransactionNotAllowedByScript(tx: Transaction, log: Log, scriptSrc: String, isTokenScript: Boolean) extends ApiError {

  override val id: Int             = 307
  override val code: StatusCode    = StatusCodes.BadRequest
  override val message: String     = s"Transaction not allowed by ${if (isTokenScript) "token" else "account"}-script"
  override lazy val json: JsObject = ScriptErrorJson(id, tx, message, scriptSrc, log)
}

object ScriptErrorJson {
  def apply(errId: Int, tx: Transaction, message: String, scriptSrc: String, log: Log): JsObject =
    Json.obj(
      "error"       -> errId,
      "message"     -> message,
      "transaction" -> tx.json(),
      "script"      -> scriptSrc,
      "vars" -> Json.arr(log.map {
        case (k, Right(v))  => Json.obj("name" -> k, "value" -> JsString(v.toString))
        case (k, Left(err)) => Json.obj("name" -> k, "error" -> JsString(err))
      })
    )
}
