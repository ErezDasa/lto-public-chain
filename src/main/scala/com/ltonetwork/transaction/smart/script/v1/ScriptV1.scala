package com.ltonetwork.transaction.smart.script.v1

import com.ltonetwork.{crypto, utils}
import com.ltonetwork.lang.ScriptVersion.Versions.V1
import com.ltonetwork.lang.v1.compiler.Terms._
import com.ltonetwork.lang.v1.evaluator.FunctionIds._
import com.ltonetwork.lang.v1.{FunctionHeader, ScriptEstimator, Serde}
import com.ltonetwork.state._
import com.ltonetwork.utils.functionCosts
import monix.eval.Coeval
import com.ltonetwork.transaction.smart.script.Script

object ScriptV1 {
  private val checksumLength = 4
  private val maxComplexity  = 20 * functionCosts(FunctionHeader.Native(SIGVERIFY))()
  private val maxSizeInBytes = 8 * 1024

  def validateBytes(bs: Array[Byte]): Either[String, Unit] =
    Either.cond(bs.length <= maxSizeInBytes, (), s"Script is too large: ${bs.length} bytes > $maxSizeInBytes bytes")

  def apply(x: EXPR, checkSize: Boolean = true): Either[String, Script] =
    for {
      scriptComplexity <- ScriptEstimator(utils.dummyVarNames, functionCosts, x)
      _                <- Either.cond(scriptComplexity <= maxComplexity, (), s"Script is too complex: $scriptComplexity > $maxComplexity")
      s = new ScriptV1(x)
      _ <- if (checkSize) validateBytes(s.bytes().arr) else Right(())
    } yield s

  private class ScriptV1(override val expr: EXPR) extends Script {
    override type V = V1.type
    override val version: V   = V1
    override val text: String = expr.toString
    override val bytes: Coeval[ByteStr] =
      Coeval.evalOnce {
        val s = Array(version.value.toByte) ++ Serde.serialize(expr)
        ByteStr(s ++ crypto.secureHash(s).take(ScriptV1.checksumLength))
      }
  }
}
