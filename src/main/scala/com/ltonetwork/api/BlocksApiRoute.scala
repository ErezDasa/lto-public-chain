package com.ltonetwork.api

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.{Route, StandardRoute}
import com.ltonetwork.block.BlockHeader
import com.ltonetwork.network._
import com.ltonetwork.settings.RestAPISettings
import com.ltonetwork.state.{Blockchain, ByteStr}
import com.ltonetwork.transaction._
import io.netty.channel.group.ChannelGroup
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.{ApiResponse, ApiResponses}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.{Operation, Parameter, Parameters}
import jakarta.ws.rs.{GET, POST, Path}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import play.api.libs.json._

import scala.concurrent._
import scala.util.Try

@Path("/blocks")
@Tag(name = "blocks")
case class BlocksApiRoute(settings: RestAPISettings,
                          blockchain: Blockchain,
                          allChannels: ChannelGroup,
                          checkpointProc: Checkpoint => Task[Either[ValidationError, Option[BigInt]]])
    extends ApiRoute {

  // todo: make this configurable and fix integration tests
  val MaxBlocksPerRequest = 100
  val rollbackExecutor    = monix.execution.Scheduler.singleThread(name = "debug-rollback")

  override lazy val route =
    pathPrefix("blocks") {
      signature ~ first ~ last ~ lastHeaderOnly ~ at ~ atHeaderOnly ~ seq ~ seqHeaderOnly ~ height ~ heightEncoded ~ child ~ address ~ delay ~ checkpoint
    }

  @GET
  @Path("/address/{address}/{from}/{to}")
  @Operation(
    summary = "Get list of blocks generated by specified address"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "from",
        description = "Start block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      ),
      new Parameter(
        name = "to",
        description = "End block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      ),
      new Parameter(
        name = "address",
        description = "Wallet address",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def address: Route = (path("address" / Segment / IntNumber / IntNumber) & get) {
    case (address, start, end) =>
      if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
        val blocks = JsArray(
          (start to end)
            .map { height =>
              (blockchain.blockAt(height), height)
            }
            .filter(_._1.isDefined)
            .map { pair =>
              (pair._1.get, pair._2)
            }
            .filter(_._1.signerData.generator.address == address)
            .map { pair =>
              pair._1.json() + ("height" -> Json.toJson(pair._2))
            })
        complete(blocks)
      } else complete(TooBigArrayAllocation)
  }

  @GET
  @Path("/child/{signature}")
  @Operation(
    summary = "Get children of specified block"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "signature",
        description = "Base58-encoded signature",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def child: Route = (path("child" / Segment) & get) { encodedSignature =>
    withBlock(blockchain, encodedSignature) { block =>
      val childJson = for {
        h <- blockchain.heightOf(block.uniqueId)
        b <- blockchain.blockAt(h + 1)
      } yield b.json()

      complete(childJson.getOrElse[JsObject](Json.obj("status" -> "error", "details" -> "No child blocks")))
    }
  }

  @GET
  @Path("/delay/{signature}/{blockNum}")
  @Operation(
    summary = "Average delay in milliseconds between last `blockNum` blocks starting from block with `signature`"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "signature",
        description = "Base58-encoded signature",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      ),
      new Parameter(
        name = "blockNum",
        description = "Number of blocks to count delay",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def delay: Route = (path("delay" / Segment / IntNumber) & get) { (encodedSignature, count) =>
    withBlock(blockchain, encodedSignature) { block =>
      val averageDelay = Try {
        (block.timestamp - blockchain.parent(block, count).get.timestamp) / count
      }

      complete(
        averageDelay
          .map(d => Json.obj("delay" -> d))
          .getOrElse[JsObject](Json.obj("status" -> "error", "details" -> "Internal error")))
    }
  }

  @GET
  @Path("/height/{signature}")
  @Operation(
    summary = "Get height of a block by its Base58-encoded signature"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "signature",
        description = "Base58-encoded signature",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def heightEncoded: Route = (path("height" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionBuilders.SignatureStringLength)
      complete(InvalidSignature)
    else {
      ByteStr
        .decodeBase58(encodedSignature)
        .toOption
        .toRight(InvalidSignature)
        .flatMap(s => blockchain.heightOf(s).toRight(BlockDoesNotExist)) match {
        case Right(h) => complete(Json.obj("height" -> h))
        case Left(e)  => complete(e)
      }
    }
  }

  @GET
  @Path("/height")
  @Operation(
    summary = "Get blockchain height"
  )
  def height: Route = (path("height") & get) {
    complete(Json.obj("height" -> blockchain.height))
  }

  @GET
  @Path("/at/{height}")
  @Operation(
    summary = "Get block at specified height"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "height",
        description = "Block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      )
    )
  )
  def at: Route = (path("at" / IntNumber) & get)(at(_, includeTransactions = true))

  @GET
  @Path("/headers/at/{height}")
  @Operation(
    summary = "Get block at specified height without transactions payload"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "height",
        description = "Block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      )
    )
  )
  def atHeaderOnly: Route = (path("headers" / "at" / IntNumber) & get)(at(_, includeTransactions = false))

  private def at(height: Int, includeTransactions: Boolean): StandardRoute = {
    (if (includeTransactions) {
       blockchain.blockAt(height).map(_.json())
     } else {
       blockchain.blockHeaderAndSize(height).map { case (bh, s) => BlockHeader.json(bh, s) }
     }) match {
      case Some(json) => complete(json + ("height" -> JsNumber(height)))
      case None       => complete(Json.obj("status" -> "error", "details" -> "No block for this height"))
    }
  }

  @GET
  @Path("/seq/{from}/{to}")
  @Operation(
    summary = "Get block at specified heights"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "from",
        description = "Start block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      ),
      new Parameter(
        name = "to",
        description = "To block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      )
    )
  )
  def seq: Route = (path("seq" / IntNumber / IntNumber) & get) { (start, end) =>
    seq(start, end, includeTransactions = true)
  }

  @GET
  @Path("/headers/seq/{from}/{to}")
  @Operation(
    summary = "Get block without transactions payload at specified heights"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "from",
        description = "Start block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      ),
      new Parameter(
        name = "to",
        description = "To block height",
        required = true,
        schema = new Schema(implementation = classOf[Int]),
        in = ParameterIn.PATH
      )
    )
  )
  def seqHeaderOnly: Route = (path("headers" / "seq" / IntNumber / IntNumber) & get) { (start, end) =>
    seq(start, end, includeTransactions = false)
  }

  private def seq(start: Int, end: Int, includeTransactions: Boolean): StandardRoute = {
    if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
      val blocks = JsArray((start to end).flatMap { height =>
        (if (includeTransactions) {
           blockchain.blockAt(height).map(_.json())
         } else {
           blockchain.blockHeaderAndSize(height).map { case (bh, s) => BlockHeader.json(bh, s) }
         }).map(_ + ("height" -> Json.toJson(height)))
      })
      complete(blocks)
    } else complete(TooBigArrayAllocation)
  }

  @GET
  @Path("/last")
  @Operation(
    summary = "Get last block data"
  )
  def last: Route = (path("last") & get)(last(includeTransactions = true))

  @GET
  @Path("/headers/last")
  @Operation(
    summary = "Get last block data without transactions payload"
  )
  def lastHeaderOnly: Route = (path("headers" / "last") & get)(last(includeTransactions = false))

  def last(includeTransactions: Boolean): StandardRoute = {
    complete(Future {
      {
        val height = blockchain.height

        (if (includeTransactions) {
           blockchain.blockAt(height).get.json()
         } else {
           val bhs = blockchain.blockHeaderAndSize(height).get
           BlockHeader.json(bhs._1, bhs._2)
         }) + ("height" -> Json.toJson(height))
      }
    })
  }

  @GET
  @Path("/first")
  @Operation(
    summary = "Get genesis block data"
  )
  def first: Route = (path("first") & get) {
    complete(blockchain.genesis.json() + ("height" -> Json.toJson(1)))
  }

  @GET
  @Path("/signature/{signature}")
  @Operation(
    summary = "Get block by a specified Base58-encoded signature"
  )
  @Parameters(
    Array(
      new Parameter(
        name = "signature",
        description = "Base58-encoded signature",
        required = true,
        schema = new Schema(implementation = classOf[String]),
        in = ParameterIn.PATH
      )
    )
  )
  def signature: Route = (path("signature" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionBuilders.SignatureStringLength) complete(InvalidSignature)
    else {
      ByteStr
        .decodeBase58(encodedSignature)
        .toOption
        .toRight(InvalidSignature)
        .flatMap(s => blockchain.blockById(s).toRight(BlockDoesNotExist)) match {
        case Right(block) => complete(block.json() + ("height" -> blockchain.heightOf(block.uniqueId).map(Json.toJson(_)).getOrElse(JsNull)))
        case Left(e)      => complete(e)
      }
    }
  }

  @POST
  @Path("/checkpoint")
  @Operation(
    summary = "Broadcast checkpoint of blocks"
  )
  @RequestBody(
    description = "Checkpoint message",
    content = Array(
      new Content(
        schema = new Schema(implementation = classOf[com.ltonetwork.network.Checkpoint]),
      )),
    required = true
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "Json with response or error"
      )
    ))
  def checkpoint: Route = (path("checkpoint") & post) {
    json[Checkpoint] { checkpoint =>
      checkpointProc(checkpoint)
        .runAsync(rollbackExecutor)
        .map {
          _.map(score => allChannels.broadcast(LocalScoreChanged(score.getOrElse(blockchain.score))))
        }
        .map(_.fold(ApiError.fromValidationError, _ => Json.obj("" -> "")): ToResponseMarshallable)
    }
  }
}
