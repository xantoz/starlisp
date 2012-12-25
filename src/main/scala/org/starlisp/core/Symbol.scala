package org.starlisp.core

import java.util.concurrent.atomic.AtomicLong

trait Environment {
  def getSymbols: Cell

  def chain: Environment
  def depth(x: Int = 0): Int

  def bind(sbl: Symbol, value: LispObject)

  def find(symbol: Symbol): Option[Symbol]
  def find(str: String): Option[Symbol]

  def isInterned(sym: Symbol) = find(sym.name) != None

  def intern(symbol: Symbol): Symbol
  def intern(str: String): Symbol = intern(new Symbol(str))
  def intern(str: String, value: LispObject) : Symbol = intern(new Symbol(str, value))
}

object RootEnvironment extends Environment {

  var index = new collection.mutable.HashMap[String, Symbol]

  def chain: Environment = new LexicalEnvironment(this)
  def depth(x: Int) = x + 1

  def gensym = Symbol.gensym

  def bind(sbl: Symbol, value: LispObject) {
    index.getOrElseUpdate(sbl.name, sbl).value = value
  }

  def getSymbols: Cell = {
    /*
    var symbols: Cell = null
    index.foreach{ case (name, sym) =>
      symbols = new Cell(sym, symbols)
    }
    symbols
    */
    null
  }

  def find(symbol: Symbol): Option[Symbol] = find(symbol.name)
  def find(str: String): Option[Symbol] = index.get(str)

  def intern(symbol: Symbol): Symbol = index.getOrElseUpdate(symbol.name, symbol)
}

trait RouterEnvironment {
  def find(symbol: Symbol): Option[Symbol] = find(symbol.name)
  def find(str: String): Option[Symbol]
}

class EmptyEnvironment(outer: Environment) extends RouterEnvironment {
  def find(str: String) = outer.find(str)
}

class ActiveEnvironment(outer: Environment) extends RouterEnvironment {
  var index = new collection.mutable.HashMap[String, Symbol]
  override def find(str: String) = {
    val x = index.get(str)
    if (x eq None) {
      outer.find(str)
    } else {
      x
    }
  }
}

class LexicalEnvironment(outer: Environment) extends Environment {

  var router: RouterEnvironment = new EmptyEnvironment(outer)

  private def getWritableRouter: ActiveEnvironment = {
    router match {
      case r: EmptyEnvironment => { val e = new ActiveEnvironment(outer); router = e; e }
      case r: ActiveEnvironment => r
    }
  }

  def chain: Environment = new LexicalEnvironment(this)
  def depth(x: Int = 0) = outer.depth(x + 1)

  def bind(sbl: Symbol, value: LispObject) {
    getWritableRouter.index.getOrElseUpdate(sbl.name, new Symbol(sbl.name, sbl.value)).value = value
  }

  def getSymbols: Cell = {
    /*
    var symbols: Cell = null
    index.foreach{ case (name, sym) =>
      symbols = new Cell(sym, symbols)
    }
    symbols
    */
    null
  }

  def find(symbol: Symbol): Option[Symbol] = router.find(symbol.name)
  def find(str: String): Option[Symbol] = router.find(str)

  def intern(symbol: Symbol): Symbol = getWritableRouter.index.getOrElseUpdate(symbol.name, symbol)
}

object Symbol {

  private val env = RootEnvironment

  private val genSymCounter = new AtomicLong()

  def gensym = new Symbol("G%d".format(genSymCounter.getAndIncrement()))

  val internalError = intern("internal-error")
  val t: Symbol = intern("t")
  val standardOutput = intern("*standard-output*", new LispOutputStreamWriter(System.out))
  val standardError = intern("*standard-error*", new LispOutputStreamWriter(System.err))
  val lambda = intern("lambda")
  val quote = intern("quote")
  val _if = intern("if")
  val `macro` = intern("macro")
  val in = intern("in")
  val out = intern("out")

  Symbol.t.value = Symbol.t

  def intern(sym: Symbol): Symbol = env.intern(sym)
  def intern(str: String): Symbol = intern(new Symbol(str))
  def intern(str: String, value: LispObject) : Symbol = intern(new Symbol(str, value))
}

class Symbol(var name: String = null, var value: LispObject = null) extends LispObject {
  override def toString = name.toString //if (Symbol.isInterned(this)) name else "#:%s".format(name)
  override def hashCode() = name.hashCode
  override def equals(obj: Any) = {
    obj match {
      case sym: Symbol => name.equals(sym.name)
      case _ => false
    }
  }
}