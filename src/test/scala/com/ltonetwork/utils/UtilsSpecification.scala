package com.ltonetwork.utils

import com.ltonetwork.lang.v1.compiler.Terms.{FUNCTION_CALL, TRUE}
import com.ltonetwork.lang.v1.compiler.Types.BOOLEAN
import com.ltonetwork.lang.v1.evaluator.ctx.{EvaluationContext, UserFunction}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec

class UtilsSpecification extends AnyFreeSpec with Matchers {

  "estimate()" - {
    "handles functions that depend on each other" in {
      val callee = UserFunction("callee", BOOLEAN) {
        TRUE
      }
      val caller = UserFunction("caller", BOOLEAN) {
        FUNCTION_CALL(callee.header, List.empty)
      }
      val ctx = EvaluationContext(
        typeDefs = Map.empty,
        letDefs = Map.empty,
        functions = Seq(caller, callee).map(f => f.header -> f)(collection.breakOut)
      )
      estimate(ctx).size shouldBe 2
    }
  }
}
