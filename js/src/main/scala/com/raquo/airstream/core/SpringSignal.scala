package com.raquo.airstream.core

import animus.Animatable
import com.raquo.airstream.common.SingleParentObservable
import org.scalajs.dom

import scala.util.Try

class SpringSignal[A](override protected val parent: Signal[(A, Option[A])])(implicit animatable: Animatable[A])
    extends Signal[A]
    with WritableSignal[A]
    with SingleParentObservable[(A, Option[A]), A] {

  println("xxx SpringSignal")

  private var anim: animatable.Anim = _
  private var animating             = false

  def tick(): Unit =
    dom.window.requestAnimationFrame(step)

  override def onStart(): Unit = {
    super.onStart()
    if (!animating) {
      animating = true
      tick()
    }
  }

  override def onStop(): Unit = {
    super.onStop()
    animating = false
  }

  private val step: scalajs.js.Function1[Double, Unit] = (t: Double) =>
    if (animating) {
      val isDone = animatable.tick(anim, t)
      fireQuick(animatable.fromAnim(anim))
      if (isDone) {
        animating = false
      }
      tick()
    }

  override protected[airstream] def initialValue: Try[A] = {
    val value = parent.tryNow()
    value.foreach { case (a, s) =>
      anim = animatable.toAnim(s getOrElse a, a)
    }
    value.map { case (a, s) => s getOrElse a }
  }

  override protected[airstream] val topoRank: Int = 1

  def fireQuick(value: A): Unit = {
    new Transaction({ trx =>
      externalObservers.foreach(_.onNext(value))
      internalObservers.foreach(InternalObserver.onNext(_, value, trx))
    })
  }

  override protected[airstream] def onNext(nextValue: (A, Option[A]), transaction: Transaction): Unit = {
    animatable.update(anim, nextValue._1)
    if (!animating) {
      animating = true
      tick()
    }
  }

  override protected[airstream] def onError(nextError: Throwable, transaction: Transaction): Unit = {
    new Transaction({ trx =>
      externalObservers.foreach(_.onError(nextError))
      internalObservers.foreach(InternalObserver.onError(_, nextError, trx))
    })
  }


  override protected[airstream] def onTry(nextValue: Try[(A, Option[A])], transaction: Transaction): Unit =
    nextValue.fold(onError(_, transaction), onNext(_, transaction))
}
