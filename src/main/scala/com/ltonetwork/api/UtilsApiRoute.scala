package com.ltonetwork.api

import akka.http.scaladsl.server.Route
import com.ltonetwork.crypto
import com.ltonetwork.settings.RestAPISettings
import com.ltonetwork.state.diffs.CommonValidation
import com.ltonetwork.transaction.smart.script.{Script, ScriptCompiler}
import com.ltonetwork.utils.{Base58, Time}
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.{ApiResponse, ApiResponses}
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.ws.rs.{GET, POST, Path}
import play.api.libs.json.Json

import java.security.SecureRandom

@Path("/utils")
@Tag(name = "utils")
case class UtilsApiRoute(timeService: Time, settings: RestAPISettings) extends ApiRoute {

  private def seed(length: Int) = {
    val seed = new Array[Byte](length)
    new SecureRandom().nextBytes(seed) //seed mutated here!
    Json.obj("seed" -> Base58.encode(seed))
  }

  override val route: Route = pathPrefix("utils") {
    compile ~ estimate ~ time ~ hashFast ~ hashSecure
  }

  @POST
  @Path("/script/compile")
  @Operation(
    summary = "Compiles string code to base64 script representation"
  )
  @RequestBody(
    description = "Script code",
    content = Array(
      new Content(
        schema = new Schema(implementation = classOf[String]),
      )),
    required = true
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "base64 or error"
      )
    ))
  def compile: Route = path("script" / "compile") {
    (post & entity(as[String])) { code =>
      complete(
        ScriptCompiler(code).fold(
          e => ScriptCompilerError(e), {
            case (script, complexity) =>
              Json.obj(
                "script"     -> script.bytes().base64,
                "complexity" -> complexity,
                "extraFee"   -> CommonValidation.ScriptExtraFee
              )
          }
        )
      )
    }
  }

  @POST
  @Path("/script/estimate")
  @Operation(
    summary = "Estimates compiled code in Base64 representation"
  )
  @RequestBody(
    description = "Compiled Base64 code",
    content = Array(
      new Content(
        schema = new Schema(implementation = classOf[String]),
      )),
    required = true
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "base64 or error"
      )
    ))
  def estimate: Route = path("script" / "estimate") {
    (post & entity(as[String])) { code =>
      complete(
        Script
          .fromBase64String(code)
          .left
          .map(_.m)
          .flatMap { script =>
            ScriptCompiler.estimate(script).map((script, _))
          }
          .fold(
            e => ScriptCompilerError(e), {
              case (script, complexity) =>
                Json.obj(
                  "script"     -> code,
                  "scriptText" -> script.text,
                  "complexity" -> complexity,
                  "extraFee"   -> CommonValidation.ScriptExtraFee
                )
            }
          )
      )
    }
  }

  @GET
  @Path("/time")
  @Operation(
    summary = "Current Node time (UTC)"
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "Json with time or error"
      )
    ))
  def time: Route = (path("time") & get) {
    complete(Json.obj("system" -> System.currentTimeMillis(), "NTP" -> timeService.correctedTime()))
  }

  @POST
  @Path("/hash/secure")
  @Operation(
    summary = "Return SecureHash of specified message: `blake2b(sha256(message))`"
  )
  @RequestBody(
    description = "Message to hash",
    content = Array(
      new Content(
        schema = new Schema(implementation = classOf[String]),
      )),
    required = true
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "Json with error or json like {\"message\": \"your message\",\"hash\": \"your message hash\"}"
      )
    ))
  def hashSecure: Route = (path("hash" / "secure") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.secureHash(message))))
    }
  }

  @POST
  @Path("/hash/fast")
  @Operation(
    summary = "Return `blake2b(message)` of specified message"
  )
  @RequestBody(
    description = "Message to hash",
    content = Array(
      new Content(
        schema = new Schema(implementation = classOf[String]),
      )),
    required = true
  )
  @ApiResponses(
    Array(
      new ApiResponse(
        responseCode = "200",
        description = "Json with error or json like {\"message\": \"your message\",\"hash\": \"your message hash\"}"
      )
    ))
  def hashFast: Route = (path("hash" / "fast") & post) {
    entity(as[String]) { message =>
      complete(Json.obj("message" -> message, "hash" -> Base58.encode(crypto.fastHash(message))))
    }
  }
}

object UtilsApiRoute {
  val MaxSeedSize     = 1024
  val DefaultSeedSize = 32
}
