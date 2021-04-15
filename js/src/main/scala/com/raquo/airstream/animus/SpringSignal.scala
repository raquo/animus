package com.raquo.airstream.animus

import animus.Animatable
import com.raquo.airstream.common.SingleParentObservable
import com.raquo.airstream.core.{Signal, Transaction}
import org.scalajs.dom

import scala.util.Try

class SpringSignal[A](val parent: Signal[A])(implicit animatable: Animatable[A])
    extends Signal[A]
    with SingleParentObservable[A, A] {

  private var anim: animatable.Anim = _
  private var animating             = false

  def tick(): Unit = {
    val _ = dom.window.requestAnimationFrame(step)
  }

  private val step: scalajs.js.Function1[Double, Unit] = (t: Double) => {
    animatable.tick(anim, t)
    fireQuick(animatable.fromAnim(anim))
    tick()
  }

  override protected[this] def initialValue: Try[A] = {
    val value = parent.tryNow()
    value.foreach { a =>
      anim = animatable.toAnim(a)
    }
    value
  }

  override val topoRank: Int = parent.topoRank + 1

  def fireQuick(value: A): Unit = {
    externalObservers.foreach(_.onNext(value))
    internalObservers.foreach(_.onNext(value, null))
  }

  override def onNext(nextValue: A, transaction: Transaction): Unit = {
    animatable.update(anim, nextValue)
    if (!animating) {
      animating = true
      tick()
    }
  }

  override protected[airstream] def onError(nextError: Throwable, transaction: Transaction): Unit = ()

  override protected[airstream] def onTry(nextValue: Try[A], transaction: Transaction): Unit =
    nextValue.foreach(onNext(_, null))
}
