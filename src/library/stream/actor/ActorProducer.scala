/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package stream.actor

import java.util.concurrent.ConcurrentHashMap
import org.reactivestreams.api.Consumer
import org.reactivestreams.api.Producer
import org.reactivestreams.spi.Publisher
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider

object ActorProducer {

  /**
   * Create a [[org.reactivestreams.api.Producer]] backed by a [[ActorProducer]] actor. It can be
   * attached to a [[org.reactivestreams.api.Consumer]] or be used as an input source for a
   * [[akka.stream.Flow]].
   */
  def apply[T](ref: ActorRef): Producer[T] = ActorProducerImpl(ref)

  /**
   * This message is delivered to the [[ActorProducer]] actor when the stream consumer requests
   * more elements.
   */
  @SerialVersionUID(1L) case class Request(elements: Int)

  /**
   * This message is delivered to the [[ActorProducer]] actor when the stream consumer cancels the
   * subscription.
   */
  @SerialVersionUID(1L) case object Cancel

  /**
   * INTERNAL API
   */
  object Internal {

    case class Subscribe(subscriber: Subscriber[Any])

    sealed trait LifecycleState

    case object PreSubscriber extends LifecycleState

    case object Active extends LifecycleState

    case object Canceled extends LifecycleState

    case object Completed extends LifecycleState

    case class ErrorEmitted(cause: Throwable) extends LifecycleState

  }

}

/**
 * Extend/mixin this trait in your [[akka.actor.Actor]] to make it a
 * stream producer that keeps track of the subscription life cycle and
 * requested elements.
 *
 * Create a [[org.reactivestreams.api.Producer]] backed by this actor with [[ActorProducer# a p p l y]].
 * It can be attached to a [[org.reactivestreams.api.Consumer]] or be used as an input source for a
 * [[akka.stream.Flow]]. You can only attach one subscriber to this producer.
 *
 * The life cycle state of the subscription is tracked with the following boolean members:
 * [[#i s A c t i v e]], [[#i s C o m p l e t e d]], [[#i s E r r o r E m i t t e d]], and [[#i s C a n c e l e d]].
 *
 * You send elements to the stream by calling [[#o n N e x t]]. You are allowed to send as many
 * elements as have been requested by the stream consumer. This amount can be inquired with
 * [[#t o t a l D e m a n d]]. It is only allowed to use `onNext` when `isActive` and `totalDemand > 0`,
 * otherwise `onNext` will throw `IllegalStateException`.
 *
 * When the stream consumer requests more elements the [[ActorProducer# R e q u e s t]] message
 * is delivered to this actor, and you can act on that event. The [[#t o t a l D e m a n d]]
 * is updated automatically.
 *
 * When the stream consumer cancels the subscription the [[ActorProducer# C a n c e l]] message
 * is delivered to this actor. After that subsequent calls to `onNext` will be ignored.
 *
 * You can complete the stream by calling [[#o n C o m p l e t e]]. After that you are not allowed to
 * call [[#o n N e x t]], [[#o n E r r o r]] and [[#o n C o m p l e t e]].
 *
 * You can terminate the stream with failure by calling [[#o n E r r o r]]. After that you are not allowed to
 * call [[#o n N e x t]], [[#o n E r r o r]] and [[#o n C o m p l e t e]].
 *
 * If the actor is stopped the stream will be completed, unless it was not already terminated with
 * failure, completed or canceled.
 */
trait ActorProducer[T] extends Actor {

  import ActorProducer._
  import ActorProducer.Internal._

  private val state = ActorProducerState(context.system)
  private var subscriber: Subscriber[Any] = _
  private var demand = 0L
  private var lifecycleState: LifecycleState = PreSubscriber

  /**
   * The state when the producer is active, i.e. before the subscriber is attached
   * and when an subscriber is attached. It is allowed to
   * call [[#o n C o m p l e t e]] and [[#o n E r r o r]] in this state. It is
   * allowed to call [[#o n N e x t]] in this state when [[#t o t a l D e m a n d]]
   * is greater than zero.
   */
  final def isActive = lifecycleState == Active || lifecycleState == PreSubscriber

  /**
   * Total number of requested elements from the stream consumer.
   * This actor automatically keeps tracks of this amount based on
   * incoming request messages and outgoing `onNext`.
   */
  final def totalDemand: Int = longToIntMax(demand)

  private def longToIntMax(n: Long): Int =
    if (n > Int.MaxValue) Int.MaxValue
    else n.toInt

  /**
   * The terminal state after calling [[#o n C o m p l e t e]]. It is not allowed to
   * call [[#o n N e x t]], [[#o n E r r o r]], and [[#o n C o m p l e t e]] in this state.
   */
  final def isCompleted: Boolean = lifecycleState == Completed

  /**
   * The terminal state after calling [[#o n E r r o r]]. It is not allowed to
   * call [[#o n N e x t]], [[#o n E r r o r]], and [[#o n C o m p l e t e]] in this state.
   */
  final def isErrorEmitted: Boolean = lifecycleState.isInstanceOf[ErrorEmitted]

  /**
   * The state after the stream consumer has canceled the subscription.
   * It is allowed to call [[#o n N e x t]], [[#o n E r r o r]], and [[#o n C o m p l e t e]] in
   * this state, but the calls will not perform anything.
   */
  final def isCanceled: Boolean = lifecycleState == Canceled

  /**
   * Send an element to the stream consumer. You are allowed to send as many elements
   * as have been requested by the stream consumer. This amount can be inquired with
   * [[#t o t a l D e m a n d]]. It is only allowed to use `onNext` when `isActive` and `totalDemand > 0`,
   * otherwise `onNext` will throw `IllegalStateException`.
   */
  def onNext(element: T): Unit = lifecycleState match {
    case Active | PreSubscriber ⇒
      if (demand > 0) {
        demand -= 1
        subscriber.onNext(element)
      } else
        throw new IllegalStateException(
          "onNext is not allowed when the stream has not requested elements, totalDemand was 0")
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onNext must not be called after onError")
    case Completed ⇒
      throw new IllegalStateException("onNext must not be called after onComplete")
    case Canceled ⇒ // drop
  }

  /**
   * Complete the stream. After that you are not allowed to
   * call [[#o n N e x t]], [[#o n E r r o r]] and [[#o n C o m p l e t e]].
   */
  def onComplete(): Unit = lifecycleState match {
    case Active | PreSubscriber ⇒
      lifecycleState = Completed
      if (subscriber ne null) // otherwise onComplete will be called when the subscription arrives
        subscriber.onComplete()
      subscriber = null // not used after onError
    case Completed ⇒
      throw new IllegalStateException("onComplete must only be called once")
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onComplete must not be called after onError")
    case Canceled ⇒ // drop
  }

  /**
   * Terminate the stream with failure. After that you are not allowed to
   * call [[#o n N e x t]], [[#o n E r r o r]] and [[#o n C o m p l e t e]].
   */
  def onError(cause: Throwable): Unit = lifecycleState match {
    case Active | PreSubscriber ⇒
      lifecycleState = ErrorEmitted(cause)
      if (subscriber ne null) // otherwise onError will be called when the subscription arrives
        subscriber.onError(cause)
      subscriber = null // not used after onError
    case _: ErrorEmitted ⇒
      throw new IllegalStateException("onError must only be called once")
    case Completed ⇒
      throw new IllegalStateException("onError must not be called after onComplete")
    case Canceled ⇒ // drop
  }

  override def aroundReceive(receive: Receive, msg: Any): Unit = msg match {
    case Request(elements) ⇒
      demand += elements
      super.aroundReceive(receive, msg)

    case Subscribe(sub) ⇒
      lifecycleState match {
        case PreSubscriber ⇒
          subscriber = sub
          lifecycleState = Active
          sub.onSubscribe(new ActorProducerSubscription(self))
        case ErrorEmitted(cause) ⇒ sub.onError(cause)
        case Completed ⇒ sub.onComplete()
        case Active | Canceled ⇒
          sub.onError(new IllegalStateException(s"ActorProducer [$self] can only have one subscriber"))
      }

    case Cancel ⇒
      lifecycleState = Canceled
      demand = 0
      super.aroundReceive(receive, msg)

    case _ ⇒
      super.aroundReceive(receive, msg)
  }

  override def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    // some state must survive restart
    state.set(self, ActorProducerState.State(Option(subscriber), demand, lifecycleState))
    super.aroundPreRestart(reason, message)
  }

  override def aroundPostRestart(reason: Throwable): Unit = {
    state.get(self) foreach { s ⇒
      // restore previous state 
      subscriber = s.subscriber.orNull
      demand = s.demand
      lifecycleState = s.lifecycleState
    }
    state.remove(self)
    super.aroundPostRestart(reason)
  }

  override def aroundPostStop(): Unit = {
    state.remove(self)
    if (isActive) subscriber.onComplete()
    super.aroundPostStop()
  }

}

/**
 * INTERNAL API
 */
case class ActorProducerImpl[T](ref: ActorRef) extends Producer[T] with Publisher[T] {

  import ActorProducer.Internal._

  override def getPublisher: Publisher[T] = this

  override def subscribe(sub: Subscriber[T]): Unit =
    ref ! Subscribe(sub.asInstanceOf[Subscriber[Any]])

  override def produceTo(consumer: Consumer[T]): Unit =
    getPublisher.subscribe(consumer.getSubscriber())
}

/**
 * INTERNAL API
 */
class ActorProducerSubscription[T](ref: ActorRef) extends Subscription with Serializable {

  import ActorProducer._

  override def requestMore(elements: Int): Unit =
    if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
    else ref ! Request(elements)

  override def cancel(): Unit = ref ! Cancel
}

/**
 * INTERNAL API
 * Some state must survive restarts.
 */
object ActorProducerState extends ExtensionId[ActorProducerState] with ExtensionIdProvider {

  import ActorProducer.Internal.LifecycleState

  override def get(system: ActorSystem): ActorProducerState = super.get(system)

  override def lookup = ActorProducerState

  override def createExtension(system: ExtendedActorSystem): ActorProducerState =
    new ActorProducerState

  case class State(subscriber: Option[Subscriber[Any]], demand: Long, lifecycleState: LifecycleState)

}

/**
 * INTERNAL API
 */
class ActorProducerState extends Extension {

  import ActorProducerState.State

  private val state = new ConcurrentHashMap[ActorRef, State]

  def get(ref: ActorRef): Option[State] = Option(state.get(ref))

  def set(ref: ActorRef, s: State): Unit = state.put(ref, s)

  def remove(ref: ActorRef): Unit = state.remove(ref)
}
