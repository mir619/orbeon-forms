package org.orbeon.xforms

import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.orbeon.xforms.facade.{BroadcastChannel, MessageEvent}

import scala.collection.mutable

object Session {
  import Private._

  sealed trait SessionStatus
  case object SessionActive        extends SessionStatus
  case object SessionAboutToExpire extends SessionStatus
  case object SessionExpired       extends SessionStatus

  def initialize(configuration: rpc.ConfigurationProperties, reactAfterMillis: Long): Unit = {
    configurationOpt = Some(Configuration(
      sessionId                 = configuration.sessionId,
      sessionHeartbeatEnabled   = configuration.sessionHeartbeatEnabled,
      maxInactiveIntervalMillis = configuration.maxInactiveIntervalMillis,
      reactAfterMillis          = reactAfterMillis,
    ))
  }

  def updateWithLocalNewestEventTime(): Unit =
    configurationOpt.foreach(sessionActivity(_, AjaxClient.newestEventTime, local = true))

  def sessionIsExpired(): Unit =
    configurationOpt.foreach(sessionExpiration(_, local = true))

  def expired: Boolean = sessionStatus == SessionExpired

  type SessionUpdateListener = SessionUpdate => Unit
  case class SessionUpdate(
    sessionStatus                  : SessionStatus,
    sessionHeartbeatEnabled        : Boolean,
    approxSessionExpiredTimeMillis : Long
  )

  def addSessionUpdateListener(listener: SessionUpdateListener): Unit    = sessionUpdateListeners += listener
  def removeSessionUpdateListener(listener: SessionUpdateListener): Unit = sessionUpdateListeners -= listener

  private object Private {
    case class Configuration(
      sessionId                 : String,
      sessionHeartbeatEnabled   : Boolean,
      maxInactiveIntervalMillis : Long,
      reactAfterMillis          : Long
    )

    private sealed trait SessionMessage { def sessionId: String }
    private case class SessionActivity  (override val sessionId: String, localNewestEventTime: Long) extends SessionMessage
    private case class SessionExpiration(override val sessionId: String)                             extends SessionMessage

    var configurationOpt: Option[Configuration]  = None
    var sessionStatus: SessionStatus             = SessionActive

    private var maxNewestEventTimeAmongAllPages: Long = -1L

    private lazy val sessionActivityBroadcastChannel = {
      val broadcastChannel = new BroadcastChannel("orbeon-session-activity")

      broadcastChannel.onmessage = (event: MessageEvent) => {
        for {
          sessionMessage <- sessionMessageFromJson(event.data.asInstanceOf[String])
          configuration  <- configurationOpt
          if sessionMessage.sessionId == configuration.sessionId
        } {
          sessionMessage match {
            case SessionActivity(_, localNewestEventTime) =>
              sessionActivity(configuration, localNewestEventTime, local = false)

            case SessionExpiration(_) =>
              sessionExpiration(configuration, local = false)
          }
        }
      }

      broadcastChannel
    }

    private def sessionMessageFromJson(jsonString: String): Either[Error, SessionMessage] = {
      // This could be done in a cleaner way by using circe's discriminator feature, but it is not available in JS.
      parser.parse(jsonString).flatMap { json =>
        json.as[SessionActivity] match {
          case Right(sessionActivity) => Right(sessionActivity)
          case Left(_)                => json.as[SessionExpiration]
        }
      }
    }

    val sessionUpdateListeners: mutable.ListBuffer[SessionUpdateListener] = mutable.ListBuffer.empty

    private def fireSessionUpdate(sessionUpdate: SessionUpdate): Unit = sessionUpdateListeners.foreach(_(sessionUpdate))

    def sessionActivity(configuration: Configuration, newestEventTime: Long, local: Boolean): Unit = {
      // Once the session is expired, we don't do anything anymore
      if (sessionStatus != SessionExpired) {
        maxNewestEventTimeAmongAllPages = math.max(maxNewestEventTimeAmongAllPages, newestEventTime)

        val elapsedMillisSinceLastEvent = System.currentTimeMillis() - maxNewestEventTimeAmongAllPages

        // Update session status
        sessionStatus =
          if (elapsedMillisSinceLastEvent < configuration.reactAfterMillis)
            SessionActive
          else if (elapsedMillisSinceLastEvent > configuration.maxInactiveIntervalMillis)
            SessionExpired
          else
            SessionAboutToExpire

        if (sessionStatus == SessionAboutToExpire && configuration.sessionHeartbeatEnabled) {
          // Trigger session heartbeat. This is the main location where this is done. This could be implemented
          // as a listener as well.
          AjaxClient.sendHeartBeat()
        }

        if (local) {
          // Broadcast our local newestEventTime value to other pages
          val message = SessionActivity(configuration.sessionId, newestEventTime).asJson.noSpaces
          sessionActivityBroadcastChannel.postMessage(message)
        }

        fireSessionUpdate(SessionUpdate(
          sessionStatus = sessionStatus,
          sessionHeartbeatEnabled = configuration.sessionHeartbeatEnabled,
          approxSessionExpiredTimeMillis = maxNewestEventTimeAmongAllPages + configuration.maxInactiveIntervalMillis
        ))
      }
    }

    def sessionExpiration(configuration: Configuration, local: Boolean): Unit = {
      // Once the session is expired, we don't do anything anymore
      if (sessionStatus != SessionExpired) {
        sessionStatus = SessionExpired

        if (local) {
          // Broadcast our local expiration to other pages
          val message = SessionExpiration(configuration.sessionId).asJson.noSpaces
          sessionActivityBroadcastChannel.postMessage(message)
        }

        fireSessionUpdate(SessionUpdate(
          sessionStatus = sessionStatus,
          sessionHeartbeatEnabled = configuration.sessionHeartbeatEnabled,
          approxSessionExpiredTimeMillis = System.currentTimeMillis()
        ))
      }
    }
  }
}
