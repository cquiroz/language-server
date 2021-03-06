package langserver.core

import java.util.{Map => JMap}
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import com.dhpcs.jsonrpc._
import com.typesafe.scalalogging.LazyLogging
import langserver.messages._
import langserver.types._
import play.api.libs.json._
import com.dhpcs.jsonrpc.JsonRpcMessage._
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler

/**
 * A connection that reads and writes Language Server Protocol messages.
 *
 * @param s thread pool to execute commands and notifications.
 */
abstract class Connection(inStream: InputStream, outStream: OutputStream)(implicit s: Scheduler)
    extends LazyLogging with Notifications {
  private val msgReader = new MessageReader(inStream)
  private val msgWriter = new MessageWriter(outStream)
  private val activeRequestsById: JMap[Int, CancelableFuture[Unit]] =
    new ConcurrentHashMap()

  def commandHandler(method: String, command: ServerCommand): Task[ResultResponse]

  val notificationHandlers: ListBuffer[Notification => Unit] = ListBuffer.empty

  def notifySubscribers(n: Notification): Unit = n match {
    case CancelRequest(id) => cancelRequest(id)
    case _ =>
      Task.sequence {
        notificationHandlers.map(f => Task(f(n)))
      }.onErrorRecover[Any] {
        case NonFatal(e) =>
          logger.error("Failed notification handler", e)
      }.runAsync
  }

  def cancelAllActiveRequests(): Unit = {
    activeRequestsById.values().forEach(_.cancel())
  }
  private def cancelRequest(id: Int): Unit = {
    Option(activeRequestsById.get(id)).foreach { future =>
      logger.info(s"Cancelling request $id")
      future.cancel()
      activeRequestsById.remove(id)
    }
  }

  def sendNotification(params: Notification): Unit = {
    val json = Notification.write(params)
    msgWriter.write(json)
  }

  /**
   * A notification sent to the client to show a message.
   *
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def showMessage(tpe: MessageType, message: String): Unit = {
    sendNotification(ShowMessageParams(tpe, message))
  }

  /**
   * The log message notification is sent from the server to the client to ask
   * the client to log a particular message.
   *
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def logMessage(tpe: MessageType, message: String): Unit = {
    sendNotification(LogMessageParams(tpe, message))
  }

  /**
   * Publish compilation errors for the given file.
   */
  def publishDiagnostics(uri: String, diagnostics: Seq[Diagnostic]): Unit = {
    sendNotification(PublishDiagnostics(uri, diagnostics))
  }

  def start() {
    var streamClosed = false
    do {
      msgReader.nextPayload() match {
        case None => streamClosed = true

        case Some(jsonString) =>
          readJsonRpcMessage(jsonString) match {
            case Left(e) =>
              msgWriter.write(e)

            case Right(message) => message match {
              case notification: JsonRpcNotificationMessage =>
                Option(Notification.read(notification)).fold(
                  logger.error(s"No notification type exists with method=${notification.method}")
                )(_.fold(
                  errors => logger.error(s"Invalid Notification: $errors - Message: $message"),
                  notifySubscribers))

              case request: JsonRpcRequestMessage =>
                unpackRequest(request) match {
                  case (_, Left(e)) => msgWriter.write(e)
                  case (None, Right(c)) => // this is disallowed by the language server specification
                    logger.error(s"Received request without 'id'. $c")
                  case (Some(id), Right(command)) => handleCommand(request.method, id, command)
                }

              case response: JsonRpcResponseMessage =>
                // logger.debug(s"Received response: $response")

              case m =>
                logger.error(s"Received unknown message: $m")
            }
            case m => logger.error(s"Received unknown message: $m")
          }
      }
    } while (!streamClosed)
  }

  private def readJsonRpcMessage(jsonString: String): Either[JsonRpcResponseErrorMessage, JsonRpcMessage] = {
    Try(Json.parse(jsonString)) match {
      case Failure(exception) =>
        Left(JsonRpcResponseErrorMessage.parseError(exception,NoCorrelationId))

      case Success(json) =>
        Json.fromJson[JsonRpcMessage](json).fold({ errors =>
          Left(JsonRpcResponseErrorMessage.invalidRequest(JsError(errors),NoCorrelationId))
        }, Right(_))
    }
  }

  private def unpackRequest(request: JsonRpcRequestMessage): (Option[CorrelationId], Either[JsonRpcResponseErrorMessage, ServerCommand]) = {
    Option(ServerCommand.read(request))
      .fold[(Option[CorrelationId], Either[JsonRpcResponseErrorMessage, ServerCommand])](
        Some(request.id) -> Left(JsonRpcResponseErrorMessage.methodNotFound(request.method,request.id )))(
          commandJsResult => commandJsResult.fold(errors =>
            Some(request.id) -> Left(JsonRpcResponseErrorMessage.invalidParams(JsError(errors),request.id )),
            command => Some(request.id) -> Right(command)))

  }

  private def handleCommand(method: String, id: CorrelationId, command: ServerCommand): Future[Unit] = {
    val future = commandHandler(method, command).map { result =>
      val rJson = ResultResponse.write(result, id)
      msgWriter.write(rJson)
    }.onErrorRecover[Unit] {
      case NonFatal(e) =>
        logger.error(e.getMessage, e)
    }.runAsync
    id match {
      case NumericCorrelationId(value) =>
        activeRequestsById.put(value.toIntExact, future)
      case _ =>
    }
    future.onComplete { _ =>
      activeRequestsById.remove(id)
    }
    future
  }
}
