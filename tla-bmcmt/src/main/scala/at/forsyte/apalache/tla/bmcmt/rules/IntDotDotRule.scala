package at.forsyte.apalache.tla.bmcmt.rules

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.caches.IntRangeCache
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.TlaArithOper
import at.forsyte.apalache.tla.lir.values.TlaInt

/**
  * Implements the rules: SE-INT-RANGE[1-2].
  *
  * @author Igor Konnov
   */
class IntDotDotRule(rewriter: SymbStateRewriter,
                    intRangeCache: IntRangeCache) extends RewritingRule {
  private def simplifier = new ConstSimplifier()

  override def isApplicable(symbState: SymbState): Boolean = {
    symbState.ex match {
      case OperEx(TlaArithOper.dotdot, _*) => true
      case _ => false
    }
  }

  override def apply(state: SymbState): SymbState = {
    state.ex match {
      case OperEx(TlaArithOper.dotdot, elems @ _*) =>
        if (elems.length != 2)
          throw new RewriterException("Expected two arguments to .., found " + elems.length)
        val (start: Int, endInclusive: Int) = getRange(elems)
        val (newArena, rangeCell) = intRangeCache.create(state.arena, (start, endInclusive))
        state.setArena(newArena).setRex(rangeCell.toNameEx).setTheory(CellTheory())

      case _ =>
        throw new RewriterException("%s is not applicable".format(getClass.getSimpleName))
    }
  }

  private def getRange(elems: Seq[TlaEx]): (Int, Int) = {
    elems map (simplifier.simplify(_)) match {
      case Seq(ValEx(TlaInt(left)), ValEx(TlaInt(right))) =>
        if (!left.isValidInt || !right.isValidInt) {
          throw new RewriterException("Range bounds are too large to fit in scala.Int")
        }
        (left.toInt, right.toInt)

      case _ =>
        throw new RewriterException("Expected a constant integer range in .., found %s"
          .format(elems.map(UTFPrinter.apply).mkString("..")))
    }
  }
}
