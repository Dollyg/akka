/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.concurrent.ExecutionContext

import akka.Done
import akka.actor.typed.Logger
import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.EventAdapter
import akka.persistence.typed.internal.EventsourcedBehavior.MDC
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.util.Collections.EmptyImmutableSeq
import akka.util.OptionVal
import scala.util.Try

import akka.actor.Cancellable
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol.RecoveryTickEvent

/**
 * INTERNAL API: Carry state for the Persistent behavior implementation behaviors
 */
@InternalApi
private[persistence] final class EventsourcedSetup[C, E, S](
  val context:               ActorContext[InternalProtocol],
  val persistenceId:         String,
  val emptyState:            S,
  val commandHandler:        PersistentBehavior.CommandHandler[C, E, S],
  val eventHandler:          PersistentBehavior.EventHandler[S, E],
  val writerIdentity:        WriterIdentity,
  val recoveryCompleted:     S ⇒ Unit,
  val onSnapshot:            (SnapshotMetadata, Try[Done]) ⇒ Unit,
  val tagger:                E ⇒ Set[String],
  val eventAdapter:          EventAdapter[E, _],
  val snapshotWhen:          (S, E, Long) ⇒ Boolean,
  val recovery:              Recovery,
  var holdingRecoveryPermit: Boolean,
  val settings:              EventsourcedSettings,
  val internalStash:         StashBuffer[InternalProtocol]
) {
  import akka.actor.typed.scaladsl.adapter._

  val persistence: Persistence = Persistence(context.system.toUntyped)

  val journal: ActorRef = persistence.journalFor(settings.journalPluginId)
  val snapshotStore: ActorRef = persistence.snapshotStoreFor(settings.snapshotPluginId)

  val internalStashOverflowStrategy: StashOverflowStrategy = {
    val system = context.system.toUntyped.asInstanceOf[ExtendedActorSystem]
    system.dynamicAccess.createInstanceFor[StashOverflowStrategyConfigurator](settings.stashOverflowStrategyConfigurator, EmptyImmutableSeq)
      .map(_.create(system.settings.config)).get
  }

  def selfUntyped = context.self.toUntyped

  private var mdc: Map[String, Any] = Map.empty
  private var _log: OptionVal[Logger] = OptionVal.Some(context.log) // changed when mdc is changed
  def log: Logger = {
    _log match {
      case OptionVal.Some(l) ⇒ l
      case OptionVal.None ⇒
        // lazy init if mdc changed
        val l = context.log.withMdc(mdc)
        _log = OptionVal.Some(l)
        l
    }
  }

  def setMdc(newMdc: Map[String, Any]): EventsourcedSetup[C, E, S] = {
    mdc = newMdc
    // mdc is changed often, for each persisted event, but logging is rare, so lazy init of Logger
    _log = OptionVal.None
    this
  }

  def setMdc(phaseName: String): EventsourcedSetup[C, E, S] = {
    setMdc(MDC.create(persistenceId, phaseName))
    this
  }

  private var recoveryTimer: OptionVal[Cancellable] = OptionVal.None

  def startRecoveryTimer(snapshot: Boolean): Unit = {
    cancelRecoveryTimer()
    implicit val ec: ExecutionContext = context.executionContext
    val timer =
      if (snapshot)
        context.system.scheduler.scheduleOnce(settings.recoveryEventTimeout, context.self.toUntyped,
          RecoveryTickEvent(snapshot = true))
      else
        context.system.scheduler.schedule(settings.recoveryEventTimeout, settings.recoveryEventTimeout,
          context.self.toUntyped, RecoveryTickEvent(snapshot = false))
    recoveryTimer = OptionVal.Some(timer)
  }

  def cancelRecoveryTimer(): Unit = {
    recoveryTimer match {
      case OptionVal.Some(t) ⇒ t.cancel()
      case OptionVal.None    ⇒
    }
    recoveryTimer = OptionVal.None
  }

}

