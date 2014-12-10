package org.sample

import java.util.concurrent.atomic.AtomicLong

import org.openjdk.jmh.annotations.{Scope, State, Benchmark}

import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.Duration
import scala.concurrent.{CanAwait, Promise, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import ExecutionContext.Implicits.global

@State(Scope.Benchmark)
class MyBenchmark {
  val counter = new AtomicLong()
  val rounds = 100000000

  def meth(): Unit = counter.incrementAndGet()

  def makeFunc() : Unit => Unit = _ => meth()

  val func = makeFunc()

  val funcs = (0 to 100) map (_ => makeFunc())

  var funcIndex = 0

  var fastFuture = FastFuture.successful(())

  // Benchmarks suitable for average-time mode. Each iteration does `rounds` rounds.

  @Benchmark
  def manyManuallyInlined(): Any = {
    var i = 0
    while (i < rounds) {
      counter.incrementAndGet()
      i += 1
    }
  }

  @Benchmark
  def manyDirectCall(): Any = {
    var i = 0
    while (i < rounds) {
      meth()
      i += 1
    }
  }

  @Benchmark
  def manyConstantFunc(): Any = {var i = 0
    while (i < rounds) {
      func()
      i += 1
    }
  }

  @Benchmark
  def manyFirstFunc(): Any = {
    var i = 0
    while (i < rounds) {
      val func = funcs(0)
      func()

      i += 1
    }
  }

  @Benchmark
  def manySomeFunc(): Any = {
    var i = 0
    var funcIndex = 0

    while (i < rounds) {
      val func = funcs(funcIndex)
      func()
      funcIndex += 1
      if (funcIndex == funcs.size) funcIndex = 0

      i += 1
    }
  }

  @Benchmark
  def manyMapFastFuture(): Any = {
    var i = 0
    var fastFuture = FastFuture.successful(())

    while (i < rounds) {
      fastFuture = new FastFuture(fastFuture).map {
        case _ =>
          FastFuture.successful(meth())
      }

      i += 1
    }
  }

  @Benchmark
  def manyMapFastFutureFunc(): Any = {
    var i = 0
    var fastFuture = FastFuture.successful(())

    while (i < rounds) {
      fastFuture = new FastFuture(fastFuture).map {
        case _ =>
          FastFuture.successful(func())
      }

      i += 1
    }
  }

  @Benchmark
  def manyMapFastFutureFirstFunc(): Any = {
    var i = 0
    var fastFuture = FastFuture.successful(())

    while (i < rounds) {
      fastFuture = new FastFuture(fastFuture).map {
        case _ =>
          FastFuture.successful(funcs(0)())
      }

      i += 1
    }
  }

  @Benchmark
  def manyMapFastFutureSomeFunc(): Any = {
    var i = 0
    var fastFuture = FastFuture.successful(())
    var funcIndex = 0

    while (i < rounds) {
      val func = funcs(funcIndex)
      fastFuture = new FastFuture(fastFuture).map {
        case _ =>
          FastFuture.successful(func())
      }

      i += 1
      funcIndex += 1
      if (funcIndex == funcs.size) funcIndex = 0
    }
  }

  // Benchmarks suitable for throughput mode: one function call per iteration

  @Benchmark
  def oneManuallyInlined(): Any = {
    counter.incrementAndGet()
  }

  @Benchmark
  def oneDirectCall(): Any = {
    meth()
  }

  @Benchmark
  def oneConstantFunc(): Any = {
    func()
  }

  @Benchmark
  def oneFirstFunc(): Any = {
    val func = funcs(0)
    func()
  }

  @Benchmark
  def oneSomeFunc(): Any = {
    val func = funcs(funcIndex)
    func()
    funcIndex += 1
    if (funcIndex == funcs.size) funcIndex = 0
  }

  @Benchmark
  def oneMapFastFuture(): Any = {
    fastFuture = new FastFuture(fastFuture).map {
      case _ =>
        FastFuture.successful(meth())
    }
  }

  @Benchmark
  def oneMapFastFutureFunc(): Any = {
    fastFuture = new FastFuture(fastFuture).map {
      case _ =>
        FastFuture.successful(func())
    }
  }

  @Benchmark
  def oneMapFastFutureFirstFunc(): Any = {
    fastFuture = new FastFuture(fastFuture).map {
      case _ =>
        FastFuture.successful(funcs(0)())
    }
  }

  @Benchmark
  def oneMapFastFutureSomeFunc(): Any = {
    fastFuture = new FastFuture(fastFuture).map {
      case _ =>
        FastFuture.successful(funcs(funcIndex)())
        funcIndex += 1
        if (funcIndex == funcs.size) funcIndex = 0
    }
  }

}

/**
 * Copyright (C) 2009-2014 Typestrict Inc. <http://www.typestrict.com>
 * Copied from: https://raw.githubusercontent.com/akka/akka/release-2.3-dev/akka-http-core/src/main/scala/akka/http/util/FastFuture.scala
 */

class FastFuture[A](val future: Future[A]) extends AnyVal {
  import FastFuture._

  def map[B](f: A ⇒ B)(implicit ec: ExecutionContext): Future[B] =
    transformWith(a ⇒ FastFuture.successful(f(a)), FastFuture.failed)

  def flatMap[B](f: A ⇒ Future[B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(f, FastFuture.failed)

  def filter(pred: A ⇒ Boolean)(implicit executor: ExecutionContext): Future[A] =
    flatMap {
      r ⇒ if (pred(r)) future else throw new NoSuchElementException("Future.filter predicate is not satisfied")
    }

  def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext): Unit = map(f)

  def transformWith[B](f: Try[A] ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] =
    transformWith(a ⇒ f(Success(a)), e ⇒ f(Failure(e)))

  def transformWith[B](s: A ⇒ Future[B], f: Throwable ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] = {
    def strictTransform[T](x: T, f: T ⇒ Future[B]) =
      try f(x)
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(a) ⇒ strictTransform(a, s)
      case ErrorFuture(e)     ⇒ strictTransform(e, f)
      case _ ⇒ future.value match {
        case None ⇒
          val p = Promise[B]()
          future.onComplete {
            case Success(a) ⇒ p completeWith strictTransform(a, s)
            case Failure(e) ⇒ p completeWith strictTransform(e, f)
          }
          p.future
        case Some(Success(a)) ⇒ strictTransform(a, s)
        case Some(Failure(e)) ⇒ strictTransform(e, f)
      }
    }
  }

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ if (pf isDefinedAt t) FastFuture.successful(pf(t)) else future)

  def recoverWith[B >: A](pf: PartialFunction[Throwable, Future[B]])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ pf.applyOrElse(t, (_: Throwable) ⇒ future))
}

object FastFuture {
  def apply[T](value: Try[T]): Future[T] = value match {
    case Success(t) ⇒ FulfilledFuture(t)
    case Failure(e) ⇒ ErrorFuture(e)
  }
  private[this] val _successful: Any ⇒ Future[Any] = FulfilledFuture.apply
  def successful[T]: T ⇒ Future[T] = _successful.asInstanceOf[T ⇒ Future[T]]
  val failed: Throwable ⇒ Future[Nothing] = ErrorFuture.apply

  private case class FulfilledFuture[+A](a: A) extends Future[A] {
    def value = Some(Success(a))
    def onComplete[U](f: Try[A] ⇒ U)(implicit executor: ExecutionContext) = Future.successful(a).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = a
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
  }
  private case class ErrorFuture(error: Throwable) extends Future[Nothing] {
    def value = Some(Failure(error))
    def onComplete[U](f: Try[Nothing] ⇒ U)(implicit executor: ExecutionContext) = Future.failed(error).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = throw error
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
  }

  implicit class EnhancedFuture[T](val future: Future[T]) extends AnyVal {
    def fast: FastFuture[T] = new FastFuture[T](future)
  }

  def sequence[T, M[_] <: TraversableOnce[_]](in: M[Future[T]])(implicit cbf: CanBuildFrom[M[Future[T]], T, M[T]], executor: ExecutionContext): Future[M[T]] =
    in.foldLeft(successful(cbf(in))) {
      (fr, fa) ⇒ for (r ← fr.fast; a ← fa.asInstanceOf[Future[T]].fast) yield r += a
    }.fast.map(_.result())

  def fold[T, R](futures: TraversableOnce[Future[T]])(zero: R)(f: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] =
    if (futures.isEmpty) successful(zero)
    else sequence(futures).fast.map(_.foldLeft(zero)(f))

  def reduce[T, R >: T](futures: TraversableOnce[Future[T]])(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] =
    if (futures.isEmpty) failed(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(futures).fast.map(_ reduceLeft op)

  def traverse[A, B, M[_] <: TraversableOnce[_]](in: M[A])(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] =
    in.foldLeft(successful(cbf(in))) { (fr, a) ⇒
      val fb = fn(a.asInstanceOf[A])
      for (r ← fr.fast; b ← fb.fast) yield r += b
    }.fast.map(_.result())
}