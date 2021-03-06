package at.forsyte.apalache.tla.bmcmt

import java.io.{File, PrintWriter}

import at.forsyte.apalache.tla.bmcmt.profiler.{IdleSmtListener, SmtListener}
import at.forsyte.apalache.tla.bmcmt.rewriter.ConstSimplifierForSmt
import at.forsyte.apalache.tla.bmcmt.types.{BoolT, CellT, FailPredT, IntT}
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.io.UTFPrinter
import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir.values.{TlaBool, TlaInt}
import com.microsoft.z3._
import com.microsoft.z3.enumerations.Z3_lbool

import scala.collection.mutable

/**
  * An implementation of a SolverContext using Z3.
  *
  * @author Igor Konnov
  */
class Z3SolverContext(debug: Boolean = false, profile: Boolean = false) extends SolverContext {
  /**
    * A log writer, for debugging purposes.
    */
  private val logWriter: PrintWriter = initLog()

  var level: Int = 0
  var nBoolConsts: Int = 0 // the first three cells are reserved for: false and true
  var nIntConsts: Int = 0
  private val z3context: Context = new Context()
  private val z3solver = z3context.mkSolver()
  private val simplifier = new ConstSimplifierForSmt()
  private var smtListener: SmtListener = new IdleSmtListener()

  /**
    * Caching one uninterpreted sort for each cell signature. For integers, the integer sort.
    */
  private val cellSorts: mutable.Map[String, (Sort, Int)] =
    new mutable.HashMap[String, (Sort, Int)]

  /**
    * Uninterpreted functions C -> C associated with function cells.
    * Since context operations may clear function declaration,
    * we carry the context level in the map and clean the function declarations on pop.
    */
  private val funDecls: mutable.Map[String, (FuncDecl, Int)] =
    new mutable.HashMap[String, (FuncDecl, Int)]()

  /**
    * The calls to z3context.mkConst consume 20% of the running time, according to VisualVM.
    * Let's cache the constants, if Z3 cannot do it well.
    * Again, the cache carries the context level, to support push and pop.
    */
  private val constCache: mutable.Map[String, (Expr, CellT, Int)] =
    new mutable.HashMap[String, (Expr, CellT, Int)]

  val falseConst: String = introBoolConst() // introduce $B$0
  val trueConst: String = introBoolConst()  // introduce $B$1
  assertGroundExpr(NameEx(trueConst))
  assertGroundExpr(OperEx(TlaBoolOper.not, NameEx(falseConst)))

  /**
    * Sometimes, we lose a fresh arena in the rewriting rules. As this situation is very hard to debug,
    * we are tracking here the largest cell id per SMT context. If the user is trying to add a cell
    * with a smaller id, there is a good chance that a fresher arena was overwritten with an older one.
    * To find bugs as soon as possible, we crash immediately.
    */
  private var maxCellIdPerContext = List(-1)

  /**
    * Dispose whatever has to be disposed in the end.
    */
  override def dispose(): Unit = {
    z3context.close()
    logWriter.close()
    constCache.clear()
    funDecls.clear()
    cellSorts.clear()
  }

  /**
    * Declare a constant for an arena cell.
    *
    * @param cell a (previously undeclared) cell
    */
  override def declareCell(cell: ArenaCell): Unit = {
    smtListener.onIntroCell(cell.id)

    if (maxCellIdPerContext.head >= cell.id) {
      // Checking consistency. When the user accidentally replaces a fresh arena with an older one,
      // we report it immediately. Otherwise, it is very hard to find the cause.
      logWriter.flush() // flush the SMT log
      throw new InternalCheckerError("Declaring cell %d, while cell %d has been already declared. Damaged arena?"
        .format(cell.id, maxCellIdPerContext.head), NullEx)
    } else {
      maxCellIdPerContext = cell.id +: maxCellIdPerContext.tail
    }

    log(s";; declare cell($cell): ${cell.cellType}")
    val cellSort = getOrMkCellSort(cell.cellType)
    log(s"(declare-const $cell $cellSort)")
    val cellName = cell.toString
    z3context.mkConstDecl(cellName, cellSort)
    val const = z3context.mkConst(cellName, cellSort)
    constCache += (cellName -> (const, cell.cellType, level))

    if (cell.id <= 1) {
      // Either FALSE or TRUE. Enforce its value explicitely
      val expr =
        if (cell.id == 0) OperEx(TlaBoolOper.not, NameEx(cellName))
        else NameEx(cellName)
      assertGroundExpr(expr)
    }
  }

  override def declareInPredIfNeeded(set: ArenaCell, elem: ArenaCell): Unit = {
    val elemT = elem.cellType
    val setT = set.cellType
    val name = s"in_${elemT.signature}${elem}_${setT.signature}$set"
    if (!constCache.contains(name)) {
      smtListener.onIntroSmtConst(name)
      log(s";; declare edge predicate $name: Bool")
      log(s"(declare-const $name Bool)")
      nBoolConsts += 1
      val const = z3context.mkConst(name, z3context.getBoolSort)
      constCache += (name -> (const, BoolT(), level))
    }
  }

  /**
    * Check whether the current view of the SMT solver is consistent with arena.
    *
    * @param arena an arena
    */
  override def checkConsistency(arena: Arena): Unit = {
    val topId = arena.topCell.id
    if (maxCellIdPerContext.head > topId) {
      // Checking consistency. When the user accidentally replaces a fresh arena with an older one,
      // we report it immediately. Otherwise, it is very hard to find the cause.
      logWriter.flush() // flush the SMT log
      throw new InternalCheckerError(s"Current arena has cell id = $topId, while SMT has ${maxCellIdPerContext.head}. Damaged arena?", NullEx)
    }
  }

  /**
    * Assert that a Boolean TLA+ expression holds true.
    *
    * @param ex a simplified TLA+ expression over cells
    */
  def assertGroundExpr(ex: TlaEx): Unit = {
    smtListener.onSmtAssert(ex)
    log(s";; assert ${UTFPrinter.apply(ex)}")
    if (!debug) {
      simplifier.simplify(ex) match {
        case OperEx(TlaBoolOper.and, args@_*) =>
          // this optimization slows down model checking
          args foreach assertGroundExpr // break down into clauses

        case simple@_ =>
          val z3expr = toExpr(simple)
          log(s"(assert ${z3expr.toString})")
          z3solver.add(z3expr.asInstanceOf[BoolExpr])
      }
    } else {
      // preprocessing makes bugs disappear, turn it off in the debugging
      val z3expr = toExpr(ex)
      log(s"(assert ${z3expr.toString})")
      z3solver.add(z3expr.asInstanceOf[BoolExpr])
    }

    if (profile) {
      val timeBefore = System.nanoTime()
      sat()
      val timeAfter = System.nanoTime()
      val diffSec = (timeAfter - timeBefore) / 1000000000
      val diffNano = (timeAfter - timeBefore) % 1000000000
      log(";;;;;  @@ TIME TO SAT: %05d.%09d sec @@".format(diffSec, diffNano))
    }
  }

  /**
    * Evaluate a ground TLA+ expression in the current model, which is available after a call to sat().
    * This method assumes that the outcome is one of the basic types: a Boolean, integer, or a cell constant.
    * If not, it throws SmtEncodingException.
    *
    * @param ex an expression to evaluate
    * @return a TLA+ value
    */
  def evalGroundExpr(ex: TlaEx): TlaEx = {
    val z3expr = z3solver.getModel.eval(toExpr(simplifier.simplify(ex)), true)
    z3expr match {
      case b: BoolExpr =>
        val isTrue = b.getBoolValue.equals(Z3_lbool.Z3_L_TRUE)
        ValEx(if (isTrue) TlaBool(true) else TlaBool(false)) // in undefined case, just return false

      case i: IntNum =>
        ValEx(TlaInt(i.getBigInteger))

      case _ =>
        if (z3expr.isConst && z3expr.getSort.getName.toString.startsWith("Cell_")) {
          NameEx(z3expr.toString)
        } else {
          throw new SmtEncodingException("Expected an integer or Boolean, found: " + z3expr, ex)
        }
    }
  }

  private def initLog(): PrintWriter = {
    val writer = new PrintWriter(new File("log.smt"))
    if (!debug) {
      writer.println("Logging is disabled (Z3SolverContext.debug = false). Activate with --debug.")
      writer.flush()
    }
    writer
  }

  /**
    * Log message to the logging file. This is helpful to debug the SMT encoding.
    *
    * @param message message text, called by name, so evaluated only when needed
    */
  def log(message: => String): Unit = {
    if (debug) {
      logWriter.println(message)
    }
  }

  /**
    * Introduce a new Boolean constant.
    *
    * @return the name of a new constant
    */
  override def introBoolConst(): String = {
    val name = "%s%d".format(BoolTheory().namePrefix, nBoolConsts)
    smtListener.onIntroSmtConst(name)
    log(s";; declare bool $name")
    log(s"(declare-const $name Bool)")
    nBoolConsts += 1
    val const = z3context.mkConst(name, z3context.getBoolSort)
    constCache += (name -> (const, BoolT(), level))
    name
  }

  /**
    * Get the names of the active Boolean constants (not the cells of type BoolT).
    * This method is used for debugging purposes and may be slow.
    *
    * @return a list of Boolean constant that are active in the current context
    */
  override def getBoolConsts: Iterable[String] = {
    val boolTheory = BoolTheory()
    constCache.keys filter boolTheory.hasConst
  }

  /**
    * Get the names of the active integers constants (not the cells of type BoolT).
    * This method is used for debugging purposes and may be slow.
    *
    * @return a list of int constant that are active in the current context
    */
  override def getIntConsts: Iterable[String] = {
    val intTheory = IntTheory()
    constCache.keys filter intTheory.hasConst
  }

  /**
    * Introduce a new integer constant.
    *
    * @return the name of a new constant
    */
  override def introIntConst(): String = {
    val name = "%s%d".format(IntTheory().namePrefix, nIntConsts)
    smtListener.onIntroSmtConst(name)
    log(s";; declare int $name")
    log(s"(declare-const $name Int)")
    nIntConsts += 1
    val const = z3context.mkConst(name, z3context.getIntSort)
    constCache += (name -> (const, IntT(), level))
    name
  }

  /**
    * Introduce an uninterpreted function associated with a cell.
    *
    * @param argType    an argument type
    * @param resultType a result type
    * @return the name of the new function (declared in SMT)
    */
  def declareCellFun(cellName: String, argType: CellT, resultType: CellT): Unit = {
    val domSig = argType.signature
    val resSig = resultType.signature
    val funName = s"fun$cellName"
    if (funDecls.contains(funName)) {
      throw new SmtEncodingException(s"Declaring twice the function associated with cell $cellName", NullEx)
    } else {
      val domSort = getOrMkCellSort(argType)
      val cdmSort = getOrMkCellSort(resultType)
      log(s";; declare cell fun $funName")
      log(s"(declare-fun $funName ($domSort) $cdmSort)")
      val funDecl = z3context.mkFuncDecl(funName, domSort, cdmSort)
      funDecls += (funName -> (funDecl, level))
    }
  }

  private def getCellFun(cellName: String): FuncDecl = {
    val funName = s"fun$cellName"
    val funDecl = funDecls.get(funName)
    if (funDecl.isDefined) {
      funDecl.get._1
    } else {
      throw new SmtEncodingException(s"A function associated with cell $cellName is not declared", NullEx)
    }
  }

  /**
    * Push SMT context
    */
  override def push(): Unit = {
    log("(push) ;; becomes %d".format(level + 1))
    logWriter.flush() // good time to flush
    z3solver.push()
    maxCellIdPerContext = maxCellIdPerContext.head +: maxCellIdPerContext
    level += 1
  }

  /**
    * Pop SMT context
    */
  override def pop(): Unit = {
    if (level > 0) {
      log("(pop) ;; becomes %d".format(level - 1))
      logWriter.flush() // good time to flush
      z3solver.pop()
      maxCellIdPerContext = maxCellIdPerContext.tail
      level -= 1
      // clean the caches
      cellSorts.retain((_, value) => value._2 <= level)
      funDecls.retain((_, value) => value._2 <= level)
      constCache.retain((_, value) => value._3 <= level)
    }
  }

  override def pop(n: Int): Unit = {
    if (n > 0) {
      log("(pop %d) ;; becomes %d".format(n, level - n))
      logWriter.flush() // good time to flush
      z3solver.pop(n)
      maxCellIdPerContext = maxCellIdPerContext.drop(n)
      level -= n
      // clean the caches
      cellSorts.retain((_, value) => value._2 <= level)
      funDecls.retain((_, value) => value._2 <= level)
      constCache.retain((_, value) => value._3 <= level)
    }
  }

  override def sat(): Boolean = {
    log("(check-sat)")
    val status = z3solver.check()
    log(s";; sat = ${status == Status.SATISFIABLE}")
    logWriter.flush() // good time to flush
    if (status == Status.UNKNOWN) {
      // that seems to be the only reasonable behavior
      throw new SmtEncodingException("SMT solver reports UNKNOWN. Perhaps, your specification is outside the supported logic.", NullEx)
    }
    status == Status.SATISFIABLE
  }

  override def satOrTimeout(timeoutSec: Long): Option[Boolean] = {
    if (timeoutSec <= 0) {
      Some(sat())
    } else {
      def setTimeout(tm: Long): Unit = {
        val params = z3context.mkParams()
        params.add("timeout", BigInt(tm).toInt)
        z3solver.setParameters(params)
      }
      // temporarily, change the timeout
      setTimeout(timeoutSec * 1000)
      log("(check-sat)")
      val status = z3solver.check()
      // set it to the maximum: Z3 is using 2^32 - 1, which is hard to pass in Java, so we can only set it to 2^31-1
      setTimeout(Int.MaxValue)
      logWriter.flush() // good time to flush
      status match {
        case Status.SATISFIABLE => Some(true)
        case Status.UNSATISFIABLE => Some(false)
        case Status.UNKNOWN => None
      }

      /*
      // XXX: this produces a segfault sometimes...
      val satFuture: Future[Boolean] = Future {
        sat()
      }(ExecutionContext.global)

      try {
        Some(Await.result(satFuture, Duration(timeoutSec, TimeUnit.SECONDS)))
      } catch {
        case _: TimeoutException | _: InterruptedException =>
          z3context.interrupt()
          log(s";; timeout")
          None
      }
      */
    }
  }

  /**
    * Print the collected constraints in the SMTLIB2 format using Z3 API.
    *
    * @return a long string of statements in SMTLIB2 format as provided by Z3
    */
  def toSmtlib2: String = {
    z3solver.toString
  }

  def setSmtListener(listener: SmtListener): Unit = {
    smtListener = listener
  }

  private def getOrMkCellSort(cellType: CellT): Sort = {
    val sig = "Cell_" + cellType.signature
    val sort = cellSorts.get(sig)
    if (sort.isDefined) {
      sort.get._1
    } else {
      val newSort =
        cellType match {
          case BoolT() =>
            z3context.getBoolSort

          case IntT() =>
            z3context.getIntSort

          case FailPredT() =>
            z3context.getBoolSort

          case _ =>
            log(s"(declare-sort $sig 0)")
            z3context.mkUninterpretedSort(sig)
        }

      cellSorts += (sig -> (newSort, level))
      newSort
    }
  }

  /*
  // this is the old implementation that used uninterpreted functions
  private def getOrMkInPred(setType: CellT, elemType: CellT): FuncDecl = {
    val name = s"in_${elemType.signature}_${setType.signature}"
    val funDecl = funDecls.get(name)
    if (funDecl.isDefined) {
      funDecl.get._1
    } else {
      val elemSort = getOrMkCellSort(elemType)
      val setSort = getOrMkCellSort(setType)
      val newDecl = z3context.mkFuncDecl(name,
        Array[Sort](elemSort, setSort), z3context.getBoolSort)
      funDecls += (name -> (newDecl, level))
      log(s"(declare-fun $name ($elemSort $setSort) Bool)") // log declaration
      newDecl
    }
  }
  */

  private def getInPred(setName: String, setT: CellT, elemName: String, elemT: CellT): Expr = {
    val name = s"in_${elemT.signature}${elemName}_${setT.signature}$setName"

    constCache.get(name) match {
      case None =>
        throw new IllegalStateException(s"Trying to use in($elemName, $setName) while $elemName is not in $setName arena")

      case Some((const, _, _)) =>
        const
    }
  }

  private def toExpr(ex: TlaEx): Expr = {
    ex match {
      case NameEx(name) =>
        constCache(name)._1 // the cached expression

      case ValEx(TlaBool(false)) =>
        z3context.mkFalse()

      case ValEx(TlaBool(true)) =>
        z3context.mkTrue()

      case ValEx(TlaInt(num)) =>
        if (num.isValidLong) {
          z3context.mkInt(num.toLong)
        } else {
          throw new SmtEncodingException("A number constant is too large to fit in Long: " + num, NullEx)
        }

      case OperEx(oper: TlaArithOper, lex, rex) =>
        // convert to an arithmetic expression
        toArithExpr(ex)

      case OperEx(oper: TlaArithOper, subex) =>
        // convert to an arithmetic expression
        toArithExpr(ex)

      case OperEx(TlaOper.eq, lhs@NameEx(lname), rhs@NameEx(rname)) =>
        if (CellTheory().hasConst(lname) && CellTheory().hasConst(rname)) {
          // just comparing cells
          z3context.mkEq(constCache(lname)._1, constCache(rname)._1)
        } else {
          // comparing integers and Boolean to cells, Booleans to Booleans, and Integers to Integers
          toEqExpr(toExpr(lhs), toExpr(rhs))
        }

      case OperEx(TlaOper.eq, lhs, rhs) =>
        toEqExpr(toExpr(lhs), toExpr(rhs))

      case OperEx(TlaOper.ne, lhs, rhs) =>
        z3context.mkNot(toExpr(OperEx(TlaOper.eq, lhs, rhs)).asInstanceOf[BoolExpr])

      case OperEx(TlaBoolOper.and, es@_*) =>
        if (es.size < 1000) {
          val newEs = es.map(e => toExpr(e).asInstanceOf[BoolExpr])
          z3context.mkAnd(newEs: _*)
        } else {
          // use Tseitin to produce many short clauses, as our clauses sometimes contain thousands of literals
          tseitinAnd(es)
        }

      case OperEx(TlaBoolOper.or, es@_*) =>
        if (es.size < 1000) {
          val mapped_es = es map toExpr
          // check the assertion before casting, to make debugging easier
          assert(mapped_es.forall(e => e.isInstanceOf[BoolExpr]))
          val cast_es = mapped_es.map(_.asInstanceOf[BoolExpr])
          z3context.mkOr(cast_es: _*)
        } else {
          // use Tseitin to produce many short clauses, as our clauses sometimes contain thousands of literals
          tseitinOr(es)
        }

      case OperEx(TlaBoolOper.implies, lhs, rhs) =>
        val lhsZ3 = toExpr(lhs).asInstanceOf[BoolExpr]
        val rhsZ3 = toExpr(rhs).asInstanceOf[BoolExpr]
        z3context.mkImplies(lhsZ3, rhsZ3)

      case OperEx(TlaBoolOper.equiv, lhs, rhs) =>
        val lhsZ3 = toExpr(lhs).asInstanceOf[BoolExpr]
        val rhsZ3 = toExpr(rhs).asInstanceOf[BoolExpr]
        z3context.mkEq(lhsZ3, rhsZ3)

      case OperEx(TlaControlOper.ifThenElse, cond, thenExpr, elseExpr) =>
        val boolCond = toExpr(cond).asInstanceOf[BoolExpr]
        val thenZ3 = toExpr(thenExpr)
        val elseZ3 = toExpr(elseExpr)
        z3context.mkITE(boolCond, thenZ3, elseZ3)

      case OperEx(TlaBoolOper.not, e) =>
        val exZ3 = toExpr(e).asInstanceOf[BoolExpr]
        z3context.mkNot(exZ3)

      case OperEx(TlaSetOper.notin, elem, set) =>
        z3context.mkNot(toExpr(OperEx(TlaSetOper.in, elem, set)).asInstanceOf[BoolExpr])

      case OperEx(TlaSetOper.in, NameEx(elemName), NameEx(setName)) =>
        val setEntry = constCache(setName)
        val elemEntry = constCache(elemName)
        getInPred(setName, setEntry._2, elemName, elemEntry._2)

      // the old implementation allowed us to do that, but the new one is encoding edges directly
    case OperEx(TlaSetOper.in, ValEx(TlaInt(_)), NameEx(_))
        | OperEx(TlaSetOper.in, ValEx(TlaBool(_)), NameEx(_)) =>
        throw new InvalidTlaExException("Preprocessing introduced a literal inside tla.in: " + ex, ex)

      case _ =>
        throw new InvalidTlaExException("Unexpected TLA+ expression: " + ex, ex)
    }
  }

  private def toEqExpr(le: Expr, re: Expr) = {
    (le, re) match {
      case (_: BoolExpr, _: BoolExpr)
           | (_: IntExpr, _: IntExpr) =>
        z3context.mkEq(le, re)

      case (_: IntExpr, _: Expr) =>
        // comparing an integer constant and a cell of integer type, which is declared as integer
        z3context.mkEq(le, re)

      case (_: Expr, _: IntExpr) =>
        // comparing an integer constant and a cell of integer type, which is declared as integer
        z3context.mkEq(le, re)

      case (_: Expr, _: Expr) =>
        // comparing a cell constant against a function application
        z3context.mkEq(le, re)

      case _ =>
        throw new CheckerException("Incomparable expressions", NullEx)
    }
  }

  private def toArithExpr(ex: TlaEx): Expr = {
    ex match {
      case ValEx(TlaInt(num)) =>
        if (num.isValidLong) {
          z3context.mkInt(num.toLong)
        } else {
          throw new SmtEncodingException("A number constant is too large to fit in Long: " + num, ex)
        }

      case NameEx(name) =>
        z3context.mkIntConst(name) // TODO: incompatible sorts?

      case OperEx(TlaArithOper.lt, left, right) =>
        z3context.mkLt(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.le, left, right) =>
        z3context.mkLe(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.gt, left, right) =>
        z3context.mkGt(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.ge, left, right) =>
        z3context.mkGe(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.plus, left, right) =>
        z3context.mkAdd(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.minus, left, right) =>
        z3context.mkSub(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.mult, left, right) =>
        z3context.mkMul(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.div, left, right) =>
        z3context.mkDiv(toArithExpr(left).asInstanceOf[ArithExpr],
          toArithExpr(right).asInstanceOf[ArithExpr])

      case OperEx(TlaArithOper.mod, left, right) =>
        z3context.mkMod(toArithExpr(left).asInstanceOf[IntExpr],
          toArithExpr(right).asInstanceOf[IntExpr])

      case OperEx(TlaArithOper.uminus, subex) =>
        z3context.mkUnaryMinus(toArithExpr(subex).asInstanceOf[IntExpr])

      case OperEx(TlaControlOper.ifThenElse, cond, thenExpr, elseExpr) =>
        val boolCond = toExpr(cond).asInstanceOf[BoolExpr]
        val thenZ3 = toArithExpr(thenExpr)
        val elseZ3 = toArithExpr(elseExpr)
        z3context.mkITE(boolCond, thenZ3, elseZ3)


      case _ =>
        throw new InvalidTlaExException("Unexpected arithmetic expression: " + ex, ex)
    }

  }

  private def tseitinOr(es: Seq[TlaEx]): BoolExpr = {
    def processOr(in: BoolExpr, form: BoolExpr, out: BoolExpr): BoolExpr = {
      log(s";; Tseitin of OR for $form")
      z3solver.add(z3context.mkOr(in, form, z3context.mkNot(out)))
      z3solver.add(z3context.mkOr(z3context.mkNot(in), out))
      z3solver.add(z3context.mkOr(z3context.mkNot(form), out))
      out
    }

    def each(past: BoolExpr, oneEx: TlaEx): BoolExpr = {
      oneEx match {
        case ValEx(TlaBool(false)) =>
          past

        case ValEx(TlaBool(true)) =>
          z3context.mkTrue()

        case NameEx(_) =>
          // just a Boolean constant
          val out = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          processOr(past, toExpr(oneEx).asInstanceOf[BoolExpr], out)

        case _ =>
          // a complex expression, introduce a Boolean constant
          val pred = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          z3solver.add(z3context.mkIff(pred, toExpr(oneEx).asInstanceOf[BoolExpr]))
          val out = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          processOr(past, pred, out)
      }
    }

    es.foldLeft(z3context.mkFalse())(each)
  }

  private def tseitinAnd(es: Seq[TlaEx]): BoolExpr = {
    def not = z3context.mkNot _

    def processAnd(in: BoolExpr, form: BoolExpr, out: BoolExpr): BoolExpr = {
      log(s";; Tseitin of AND for $form")
      z3solver.add(z3context.mkOr(not(in), not(form), out))
      z3solver.add(z3context.mkOr(in, not(out)))
      z3solver.add(z3context.mkOr(form, not(out)))
      out
    }

    def each(past: BoolExpr, oneEx: TlaEx): BoolExpr = {
      oneEx match {
        case ValEx(TlaBool(true)) =>
          past

        case ValEx(TlaBool(false)) =>
          z3context.mkFalse()

        case NameEx(_) =>
          // just a Boolean constant
          val out = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          processAnd(past, toExpr(oneEx).asInstanceOf[BoolExpr], out)

        case _ =>
          // a complex expression, introduce a Boolean constant
          val pred = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          z3solver.add(z3context.mkIff(pred, toExpr(oneEx).asInstanceOf[BoolExpr]))
          val out = constCache(introBoolConst())._1.asInstanceOf[BoolExpr]
          processAnd(past, pred, out)
      }
    }

    es.foldLeft(z3context.mkTrue())(each)
  }
}
