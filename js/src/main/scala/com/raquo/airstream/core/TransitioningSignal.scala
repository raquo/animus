package com.raquo.airstream.core

import animus.{OrderedSet, Transition, TransitionStatus}
import com.raquo.airstream.common.{InternalNextErrorObserver, SingleParentObservable}
import com.raquo.airstream.state.Var

import scala.collection.mutable
import scala.scalajs.js.timers.{SetTimeoutHandle, clearTimeout, setTimeout}
import scala.util.Try

class TransitioningSignal[Input, Output, Key](
    override protected[this] val parent: Signal[Seq[Input]],
    getKey: Input => Key,
    project: (Key, Input, Signal[Input], Transition) => Output
) extends Signal[Seq[Output]]
    with WritableSignal[Seq[Output]]
    with SingleParentObservable[Seq[Input], Seq[Output]]
    with InternalNextErrorObserver[Seq[Input]] {

  println("xxx TransitioningSignal")

  override protected[this] def initialValue: Try[Seq[Output]] = parent.tryNow().map(memoizedProject(_, true))

  override protected[airstream] val topoRank: Int = 1

  private[this] val timeoutHandles: mutable.Map[Key, SetTimeoutHandle]                      = mutable.Map.empty
  private[this] val memoized: mutable.Map[Key, (Output, Var[Input], Var[TransitionStatus])] = mutable.Map.empty
  private[this] val activeKeys: mutable.Set[Key]                                            = mutable.Set.empty

  // Used to track the order of the values.
  private[this] val ordered = new OrderedSet[Key](Vector.empty)

  protected override def onStop(): Unit = {
    memoized.clear()
    super.onStop()
  }

  override protected[airstream] def onNext(nextInputs: Seq[Input], transaction: Transaction): Unit = {
    new Transaction(trx => fireValue(memoizedProject(nextInputs), trx))
  }

  def refireMemoized(): Unit = new Transaction(trx => fireValue(ordered.toList.map(memoized(_)._1), trx))

  override protected[airstream] def onError(nextError: Throwable, transaction: Transaction): Unit = {
    fireError(nextError, transaction)
  }

  private[this] def memoizedProject(nextInputs: Seq[Input], first: Boolean = false): Seq[Output] = {
    val nextKeysDict = mutable.HashSet.empty[Key] // HashSet has desirable performance tradeoffs

    val nextOutputs = nextInputs.map { input =>
      val key = getKey(input)
      activeKeys.add(key)
      nextKeysDict.add(key)

      memoized.get(key) match {
        case Some((output, inputVar, statusVar)) =>
          // If it was being removed, clear the timeout and make it active
          if (statusVar.now() == TransitionStatus.Removing) {
            clearTimeout(timeoutHandles(key))
            statusVar.set(TransitionStatus.Active)
          }
          // Update the input if it has changed for this key
          if (inputVar.now() != input) inputVar.set(input)
          key -> output

        case None =>
          val inputVar                        = Var(input)
          val initialStatus: TransitionStatus = if (first) TransitionStatus.Active else TransitionStatus.Inserting
          val transitionStatusVar             = Var(initialStatus)
          val output                          = project(key, input, inputVar.signal, Transition(transitionStatusVar.signal))
          transitionStatusVar.set(TransitionStatus.Active)
          memoized(key) = (output, inputVar, transitionStatusVar)
          key -> output
      }
    }

    ordered.addValues(nextOutputs.map(_._1))

    val removing = activeKeys.toSet -- nextKeysDict

    removing.foreach { key =>
      activeKeys.remove(key)
      memoized.get(key).map(_._3).foreach(_.set(TransitionStatus.Removing))
      val handle = setTimeout(950) {
        memoized.remove(key)
        ordered.remove(key)
        refireMemoized()
      }
      timeoutHandles(key) = handle
    }

    ordered.toList.map { memoized(_)._1 }
  }

}
