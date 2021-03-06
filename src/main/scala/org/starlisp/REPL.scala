package org.starlisp

import core._

object REPL extends App {
  val runtime = Runtime.createAndBootstrap
  val out = Symbol.standardOutput.value.asInstanceOf[LispOutputStream]
  val in = runtime.standardInput.value.asInstanceOf[LispInputStream]
  out.write("Hello and welcome to starlisp!\n")
  while(!runtime.stopped) {
    try {
      while(!runtime.stopped) {
        out.write("\n>> ")
        runtime.prin1(runtime.eval(runtime.read(in)), out)
      }
    } catch {
      case e: LispException => println(e.getMessage)
      case e: Exception => e.printStackTrace;
    }
  }
}