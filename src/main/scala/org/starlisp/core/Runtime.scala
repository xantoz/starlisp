package org.starlisp.core

import java.io._
import scala.Predef._
import scala.Some
import java.util.Date

// TODO: in order to make runtime construction cheaper, we should move non-runtime specific interns
//       to a System type object initializer so runtime construction
object Runtime {
  def createAndBootstrap: Runtime = {
    val runtime = new Runtime
    val bootstrap = FileUtils.readResourceFile(this.getClass, "/bootstrap.ljsp")
    val is = new StringInputStream(runtime.systemEnv, bootstrap)
    try {
      while(!runtime.stopped) {
        try {
          val expr = runtime.read(is)
          runtime.eval(expr)
        } catch {
          case e: LispException => ;//println(e.getMessage)
        }
      }
    } finally {
      runtime.stopped = false
      is.close
    }
    runtime
  }
}

class Runtime(val systemEnv: Environment = RootEnvironment.chain) {

  import Symbol._

  val nil = null
  type Args = Array[LispObject]

  var stopped = false

  val runtimeEnv = systemEnv.chain
  val globalEnv = runtimeEnv.chain

  private def error(msg: String): LispObject = {
    throw new LispException(internalError, msg)
    nil
  }

  private def cons(car: LispObject, cdr: LispObject = null): Cell = new Cell(car, cdr)

  private def evlis(list: Cell, env: Environment): Cell = {
    if (list == null) return null
    var last = new Cell(eval(list.car, env))
    val result = last
    var c = list.rest
    while (c != null) {
      last = last.Cdr(new Cell(eval(c.car, env)))
      c = c.rest
    }
    result
  }

  def inputStreamFromString(string: String): LispInputStream = {
    new StringInputStream(globalEnv, string)
  }

  def eval(obj: LispObject, env: Environment = globalEnv): LispObject = {
    obj match {
      case symbol: Symbol => {
        if (symbol.value eq null) {
          val sym = env.find(symbol)
          if (sym eq None)
            error("The variable %s is unbound.".format(symbol.name))
          else
            sym.get.value
        } else {
          symbol.value
        }
      }
      case list: Cell => {
        eval(list.car, env) match {
          case first: Cell => {
            val fn = first.car
            if (fn eq lambda) {
              evalLambda(list.rest, first.rest, env.chain)
            } else if (fn eq `macro`) {
              evalmacro(list, first.rest, env)
            } else {
              error("%s is not a function.".format(list.car.toString))
            }
          }
          case proc: Procedure => proc(env, list, eval)
          case unknown => {
            error("EVAL: %s is not a function name; try using a symbol instead. EXPR: %s".format(
                  String.valueOf(list.car),
                  LispObject.toStringOrNil(obj)))
          }
        }
      }
      case _ => obj
    }
  }

  private def evalmacro(list: Cell, second: Cell, env: Environment): LispObject = {
    eval(eval(cons(cons(lambda, second), cons(cons(quote, cons(list)))), env), env)
  }

  private def pairlis(cell: Cell, argsList: Cell, env: Environment) {
    var vars = cell
    var args = argsList
    var done = false
    while (!done) {
      if (vars.cdr == null) {
        if (args.cdr != null) error("Too many args: " + argsList)
        env.bind(vars.Car[Symbol], args.car)
        done = true
      } else if (!(vars.cdr.isInstanceOf[Cell])) {
        env.bind(vars.Car[Symbol], args.car)
        env.bind(vars.Cdr[Symbol], args.cdr)
        done = true
      } else {
        env.bind(vars.Car[Symbol], args.car)
        args = args.rest
        if (argsList == null) error("Too few args: " + argsList)
        vars = vars.rest
      }
    }
  }

  private def evalLambda(args: Cell, second: Cell, env: Environment): LispObject = {
    var lambdaBody = second.rest
    if (lambdaBody == null) return null // TODO: fix?
    val lambdaVar = second.car
    (Option(lambdaVar), Option(evlis(args, env))) match {
      case (Some(symbol: Symbol), Some(argsList: Cell)) => env.bind(symbol, argsList)
      case (Some(head: Cell), Some(argsList: Cell)) => pairlis(head, argsList, env)
      case (Some(cell: Cell), None) => error("Too few args (zero in fact): " + args)
      case (_, _) => ;
    }
    while (lambdaBody.cdr != null) {
      eval(lambdaBody.car, env)
      lambdaBody = lambdaBody.rest
    }
    eval(lambdaBody.car, env)
  }

  // Initialize the Runtime-specific methods
  private def intern(proc: Procedure) {
    runtimeEnv.intern(proc.name).value = proc
  }
  def intern(str: String): Symbol = intern(new Symbol(str))
  def intern(str: String, value: LispObject) : Symbol = intern(new Symbol(str, value))
  def intern(sym: Symbol): Symbol = runtimeEnv.intern(sym)

  val standardInput = runtimeEnv.intern("*standard-input*", new LispInputStreamReader(runtimeEnv, System.in))

  def read(stream: LispInputStream): LispObject = {
    Option(stream).getOrElse(standardInput.value).as[LispInputStream].read
  }

  def readChar(stream: LispInputStream): LispChar = {
    Option(stream).getOrElse(standardInput.value).as[LispInputStream].readChar
  }

  def prin1(obj: LispObject, stream: LispOutputStream): LispObject = {
    val s = if (stream != nil) stream else standardOutput.value.as[LispOutputStream]
    if (obj != nil) {s.write(obj.toString)} else {s.write("nil")}
    obj
  }
  def writeChar(ch: LispChar, stream: LispOutputStream): LispChar = {
    (if (stream != nil) stream else standardOutput.value).as[LispOutputStream].write(ch.ch)
    ch
  }

  intern(new LispFn("prin1", 1, 2) {
    def apply(o: Args) = prin1(o(0), if ((o.length > 1)) o(1).as[LispOutputStream] else nil)
  })
  intern(new LispFn("write-char", 1, 2) {
    def apply(o: Args) = {
      try {
        writeChar(o(0).as[LispChar], (if (o.length > 1) o(1).as[LispOutputStream] else nil))
      } catch {
        case e: IOException => {
          throw new LispException(internalError, "An IOException just occured to me, " + this.toString)
        }
      }
    }
  })
  intern(new LispFn1[LispStream]("close") {
    def apply(a: LispStream) = {
      try {
        if (a.close) t else nil
      } catch {
        case e: IOException => {
          throw new LispException(internalError, "An IOException just ocurred to me, " + this.toString)
        }
      }
    }
  })
  intern(new LispFn("read", 0, 1) {
    def apply(o: Args) = {
      try {
        read(if (o.length > 0) o(0).as[LispInputStream] else nil)
      } catch {
        case e: IOException => {
          throw new LispException(internalError, "An IOException just ocurred to me, " + this.toString)
        }
      }
    }
  })
  intern(new LispFn("read-char", 0, 1) {
    def apply(o: Args) = {
      try {
        readChar(if (o.length > 0) o(0).as[LispInputStream] else nil)
      } catch {
        case e: IOException => {
          throw new LispException(internalError, "An IOException just occured to me, " + this.toString)
        }
      }
    }
  })
  intern(new Procedure("set") {
    def apply(env: Environment, head: Cell, eval: (LispObject, Environment) => LispObject) = {
      val list = head.rest
      if (list eq null) error("Too few args when calling procedure: " + toString)
      val symRef = eval(list.car, env)
      if (!symRef.isInstanceOf[Symbol]) {
        if (list.car.isInstanceOf[Symbol]) {
          error("%s is not bound to a symbol.".format(list.Car[Symbol].name))
        } else {
          error("%s does not reference a symbol.".format(list.car.toString))
        }
      }
      val sym = symRef.as[Symbol]
      if (list.rest eq null) error("Too few args when calling procedure: " + toString)
      val b = eval(list.rest.car, env)

      env.find(sym) match {
        case Some(foundSymbol) => {
          // TODO: should we also set the value of sym ?
          foundSymbol.value = b
        }
        case None => {
          sym.value = b
          globalEnv.intern(sym) // CMUCL style auto-promote to special
        }
      }
      b
    }
  })
  intern(new Procedure("open", 2) {
    def apply(env: Environment, head: Cell, eval: (LispObject, Environment) => LispObject) = {
      val list = head.rest
      try {
        if (list eq null) error("Too few args when calling procedure: " + toString)
        val a = eval(list.car, env).as[LispString].toJavaString
        if (list.rest eq null) error("Too few args when calling procedure: " + toString)
        val b = eval(list.rest.car, env)

        if (b eq in) new LispInputStreamReader(env, new FileReader(a))
        else if (b eq out) new LispOutputStreamWriter(new PrintWriter(new FileWriter(a)))
        else throw new LispException(internalError, "You confused me, you want a stream out, or in?")
      } catch {
        case e: IOException => {
          throw new LispException(internalError, e)
        }
      }
    }
  })
  intern(new Procedure("make-string-input-stream") {
    def apply(env: Environment, head: Cell, eval: (LispObject, Environment) => LispObject) = {
      val list = head.rest
      if (list eq null) error("Too few args when calling procedure: " + toString)
      val a = eval(list.car, env).as[LispString].toJavaString
      new LispInputStreamReader(env, new StringReader(a))
    }
  })
  intern(new LispFn("symbols") {
    def apply(o: Args) = {
      globalEnv.getSymbols
      nil // TODO: fixme
    }
  })
  intern(new LispFn("make-runnable", 1) {
    def apply(o: Args) = {
      new JavaObject(new Runnable {
        def run() { eval(cons(o(0), null), globalEnv) }
      })
    }
  })
  intern(new LispFnP[LispObject]("%try") {
    def apply(a: LispObject, b: LispObject) = {
      try {
        eval(cons(a, null), globalEnv)
      }
      catch {
        case e: Exception => {
          eval(cons(b, cons(new JavaObject(e), null)), globalEnv)
        }
      }
    }
  })
  intern(new LispFn("exit", 0, 1) {
    def apply(o: Args) = {
      stopped = true
      null
    }
  })

  private def eq(obj1: LispObject, obj2: LispObject) = if (obj1 == obj2) t else nil
  private def eql(a: LispObject, b: LispObject): LispObject = {
    if ((a eq nil) || (b eq nil))
      eq(a, b)
    else if (!a.getClass.isInstance(b))
      nil
    else if (a.isInstanceOf[LispChar])
      if (a.as[LispChar].ch == a.as[LispChar].ch) t else nil
    else if ((a.isInstanceOf[LispNumber]))
      if (a.as[LispNumber] == b.as[LispNumber]) t else nil
    else
      eq(a, b)
  }

  /*
   * Define the global "keywords" of the system.
   * At the moment, this is done largely for performance as the symbol value is already defined
   * and environment chaining isn't needed.  To enable a symbol to be redefined by an application,
   * just move it out of here and back to Runtime.
   *
   * TODO: define these via a lisp file similar to how jscheme does it.
   *
   */

  intern(new LispFn("gensym") {def apply(o: Args) = gensym})
  intern(new Procedure("eval") {
    def apply(env: Environment, head: Cell, eval: (LispObject, Environment) => LispObject) = {
      eval(head, env)
    }
  })
  intern(new Procedure("env-depth") {
    def apply(env: Environment, head: Cell, eval: (LispObject, Environment) => LispObject) = {
      LispFixnum.create(env.depth(0))
    }
  })

  intern("nil", nil)
  intern("Class", new JavaObject(classOf[java.lang.Class[_]]))

  intern(new Procedure("aeq"){
    def apply(env: Environment, list: Cell, eval: ((LispObject, Environment) => LispObject)): LispObject = {
      val evalA = eval(list.cadr, env)
      val evalB = eval(list.caddr, env)
      val areEqual = if (evalA.getClass == evalB.getClass) {
        (evalA, evalB) match {
          case (a: Cell, b: Cell) => a.toString.equals(b.toString)
          case (a, b) => a == b
        }
      } else {
        false
      }
      if (!areEqual) error("%s not equal %s".format(evalA,evalB))
      t
    }
  })

  intern(new LispFnP[LispObject]("cons") {
    def apply(a: LispObject, b:LispObject) = { new Cell(a, b) }
  })
  intern(new LispFn1[Cell]("car") {
    def apply(a: Cell) = { if (a eq nil) nil else a.car }
  })
  intern(new LispFn1[Cell]("cdr") {
    def apply(a: Cell) = { if (a eq nil) nil else a.cdr }
  })
  intern(new LispFn2[Cell, LispObject]("rplaca") {
    def apply(a: Cell, b: LispObject) = { a.Car(b); a }
  })
  intern(new LispFn2[Cell, LispObject]("rplacd") {
    def apply(a: Cell, b: LispObject) = { a.Cdr(b); a }
  })
  intern(new LispFn1[Symbol]("symbol-value") {
    def apply(a: Symbol) = { if (a eq nil) nil else a.value }
  })
  intern(new LispFn1[LispObject]("intern") {
    def apply(a: LispObject) = {
      // TODO: intern into which env?
      if (a.isInstanceOf[LispString]) intern((a.as[LispString]).toJavaString)
      else if (a.isInstanceOf[Symbol]) globalEnv.intern(a.as[Symbol])
      else throw new LispException(internalError, "Bad argument")
    }
  })
  intern(new LispFnP[LispObject]("eq?") {
    def apply(a: LispObject, b:LispObject) = { if (a == b) t else nil }
  })
  intern(new LispFn1[LispObject]("atom?") {
    def apply(a: LispObject) = if (a.isInstanceOf[Cell]) nil else t
  })
  intern(new LispFnP[LispNumber]("+") {
    def apply(a: LispNumber, b: LispNumber) = { a.add(b) }
  })
  intern(new LispFnP[LispNumber]("-") {
    def apply(a: LispNumber, b: LispNumber) = { a.sub(b) }
  })
  intern(new LispFnP[LispNumber]("*") {
    def apply(a: LispNumber, b: LispNumber) = { a.mul(b) }
  })
  intern(new LispFnP[LispNumber]("/") {
    def apply(a: LispNumber, b: LispNumber) = { a.div(b) }
  })
  intern(new LispFnP[LispInteger]("mod") {
    def apply(a: LispInteger, b: LispInteger) = { a.mod(b) }
  })
  intern(new LispFnP[LispInteger]("ash") {
    def apply(a: LispInteger, b: LispInteger) = { a.ash(b) }
  })
  intern(new LispFn1[LispNumber]("neg?") {
    def apply(a: LispNumber) = { if (a.negP) t else nil }
  })
  intern(new LispFn1[LispNumber]("sqrt") {
    def apply(a: LispNumber) = { new LispFlonum((math.sqrt(a.toJavaDouble))) }
  })
  intern(new LispFnP[LispObject]("eql?") {
    def apply(a: LispObject, b: LispObject) = eql(a, b)
  })
  intern(new LispFnP[LispNumber]("=") {
    def apply(a: LispNumber, b: LispNumber) = if (a == b) t else nil
  })
  intern(new LispFnP[LispChar]("char=") {
    def apply(a: LispChar, b: LispChar) = if (a.ch == b.ch) t else nil
  })
  intern(new LispFn2[LispArray, LispInteger]("aref") {
    def apply(a: LispArray, b: LispInteger) = a.aref(b.toJavaInt)
  })
  intern(new LispFn3[LispArray, LispInteger, LispObject]("aset") {
    def apply(a: LispArray, b: LispInteger, c: LispObject) = { a.aset(b.toJavaInt, c) }
  })
  intern(new LispFn0("time") {
    def apply() = new LispString(new Date().toString)
  })
  intern(new LispFn0("get-time") {
    def apply() = new LispFixnum(System.currentTimeMillis)
  })
  intern(new LispFn0("make-string-output-stream") {
    def apply() = new StringOutputStream
  })
  intern(new LispFn1[LispStream]("eof?") {
    def apply(a: LispStream) = if (a.eof) t else nil
  })
  intern(new LispFn("system-exit", 0, 1) {
    def apply(o: Args) = {
      System.exit(if (o.length < 1) 0 else (o(0).as[LispNumber]).toJavaInt)
      nil
    }
  })
  intern(new LispFn1[StringOutputStream]("get-output-stream-string") {
    def apply(a: StringOutputStream) = new LispString(a.getOutputStreamString)
  })
  intern(new LispFn("throw", 1, 2) {
    def apply(o: Args) = {
      if (o.length == 2) {
        if (o(1).isInstanceOf[LispString]) throw new LispException(o(0).as[Symbol], (o(1).as[LispString]).toJavaString)
        else if (o(1).isInstanceOf[JavaObject]) throw new LispException(o(0).as[Symbol], (o(1).as[JavaObject]).getObj.asInstanceOf[Throwable])
        else throw new LispException(internalError, "Throw threw a throw.")
      }
      if (o(0).isInstanceOf[JavaObject] && (o(0).as[JavaObject]).getObj.isInstanceOf[LispException]) throw (o(0).as[JavaObject]).getObj.asInstanceOf[LispException]
      throw new LispException(o(0).as[Symbol])
    }
  })
  intern(new LispFn("make-array", 1) {
    def apply(o: Args) = {
      if (o(0).isInstanceOf[Cell]) new LispArray(o(0).as[Cell])
      else if (o(0).isInstanceOf[LispInteger]) new LispArray((o(0).as[LispInteger]).toJavaInt)
      else throw new LispException(internalError, "make-array wants an integer or a list")
    }
  })
  intern(new LispFn("make-string", 2) {
    def apply(o: Args) = {
      new LispString((o(0).as[LispInteger]).toJavaInt, o(1).as[LispChar])
    }
  })
  intern(new LispFn("length", 1) {
    def apply(o: Args) = {
      new LispFixnum(
        if ((o(0) eq nil))
          0
        else if (o(0).isInstanceOf[Cell])
          (o(0).as[Cell]).length
        else
          (o(0).as[LispArray]).length)
    }
  })
  intern(new LispFnP[LispObject]("equal?") {
    def apply(a: LispObject, b: LispObject) = { if (if (a eq nil) (b eq nil) else (a == b)) t else nil }
  })
  intern(new LispFn1[LispObject]("sxhash") {
    def apply(a: LispObject) = new LispFixnum(if (a eq nil) 0 else a.hashCode)
  })
  intern(new LispFn("running-compiled?") {
    def apply(o: Args) = nil
  })
  intern(new LispFn1[LispChar]("char->integer") {
    def apply(a: LispChar) = new LispFixnum(a.ch.asInstanceOf[Int])
  })
  intern(new LispFn1[LispInteger]("integer->char") {
    def apply(a: LispInteger) = LispChar.create(a.toJavaInt.asInstanceOf[Char])
  })

  private val number: Symbol = intern("number")
  private val integer: Symbol = intern("integer")
  private val fixnum: Symbol = intern("fixnum")
  private val bignum: Symbol = intern("bignum")
  private val flonum: Symbol = intern("flonum")
  private val symbol: Symbol = intern("symbol")
  private val cons: Symbol = intern("cons")
  private val procedure: Symbol = intern("procedure")
  private val subr: Symbol = intern("subr")
  private val array: Symbol = intern("array")
  private val string: Symbol = intern("string")
  private val javaObject: Symbol = intern("java-object")
  private val javaMethod: Symbol = intern("java-method")
  //  private val exception: Symbol = intern("exception")
  private val charmander: Symbol = intern("char")
  private val stream: Symbol = intern("stream")
  private val list: Symbol = intern("list")

  intern(new LispFn2[Symbol, LispObject]("type?") {
    def apply(a: Symbol, b: LispObject) = {
      val knownType =
        if (a eq number) b.isInstanceOf[LispNumber]
        else if (a eq integer) b.isInstanceOf[LispInteger]
        else if (a eq fixnum) b.isInstanceOf[LispFixnum]
        else if (a eq bignum) b.isInstanceOf[LispBigInt]
        else if (a eq flonum) b.isInstanceOf[LispFlonum]
        else if (a eq symbol) b.isInstanceOf[Symbol]
        else if (a eq cons) b.isInstanceOf[Cell]
        else if (a eq list) ((b eq nil) || b.isInstanceOf[Cell])
        else if (a eq procedure) b.isInstanceOf[Procedure]
        else if (a eq subr) b.isInstanceOf[LispFn]
        else if (a eq array) b.isInstanceOf[LispArray]
        else if (a eq string) b.isInstanceOf[LispString]
        else if (a eq javaObject) b.isInstanceOf[JavaObject]
        else if (a eq javaMethod) b.isInstanceOf[JavaMethod]
        else if (a eq charmander) b.isInstanceOf[LispChar]
        else if (a eq stream) b.isInstanceOf[LispStream]
        else false
      if (knownType) t else nil
    }
  })
}
