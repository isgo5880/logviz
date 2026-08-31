package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.dom.Window
import io.circe.parser.*
import org.http4s.circe.*
import org.http4s.Method
import org.http4s.Request
import org.http4s.ServerSentEvent
import org.http4s.Uri
import org.http4s.client.Client

import latis.logviz.model.Event

/** EventClient used to return stream of events */
trait EventClient {
  def getEvents(startTime: String, endTime: Option[String], instance: Option[String]): Stream[IO, Event]

  //no given start and end time: past 24 hours + live
  def getEvents: Stream[IO, Event] =
    val curr = LocalDateTime.now(java.time.ZoneId.of("UTC"))
    val start = curr.toLocalDate().atStartOfDay().toString()
    getEvents(start, None, None)

  //no end time (live)
  // def getEvents(start: String): Stream[IO, Event] = 
  //   getEvents(start, None, Some("json1"))

  // def getEvents(start: String, end: String): Stream[IO, Event] = 
  //   getEvents(start, Some(end), Some("json1"))

  def getEvents(start: String, end: String, instance: String): Stream[IO, Event] = 
    getEvents(start, Some(end), Some(instance))



  def getInstances: IO[List[String]]
}

object EventClient {
  
  /** 
    * Creates EventClient
    * 
    * Creates baseURI @example localhost:8080 
    * 
    * EntityDecoder used to decode JSON which is defined in Event
    * 
    * Create an EventClient instance to expect a list of events after decoding event.json
    * 
    * @param http Client from http4s [[https://http4s.github.io/http4s-dom/fetch.html]]
    */
  def fromHttpClient(http: Client[IO]): IO[EventClient] =
    (
      Window[IO].location.protocol.get,
      Window[IO].location.host.get,
      Window[IO].location.pathname.get
    ).mapN { (protocol, host, pathname) =>
      val baseUri = Uri.unsafeFromString(s"$protocol//$host$pathname")

      new EventClient {
        override def getEvents(startTime: String, endTime: Option[String], instance: Option[String]): Stream[IO, Event] =         
          val end = endTime match {
            case Some(value) => value
            case None => LocalDateTime.now(java.time.ZoneId.of("UTC")).toString()
          }

          val uri = instance match {
            case Some(value) => 
              Uri.unsafeFromString(s"${baseUri}events?startTime=$startTime&endTime=$end&instance=$value")
            case None =>
              Uri.unsafeFromString(s"${baseUri}events?startTime=$startTime&endTime=$end")
          }

          val request = Request[IO](method = Method.GET, uri = uri)
          
          http.stream(request).flatMap{ res =>
            res.body
              .through(ServerSentEvent.decoder[IO])
              .map{
                case ServerSentEvent(Some(data), _, _, _, _) =>
                  parse(data) match {
                    case Right(json) => json.as[Event] match {
                      case Right(event) => Some(event)
                      case Left(error) => throw new Exception(s"Error parsing json to event with error $error")
                    }
                    case Left(error) => throw new Exception(s"Error parsing server sent event to json with error $error")
                  }
                case _ => None
              }
              .unNone
          }

        override def getInstances: IO[List[String]] = {
          val uri = Uri.unsafeFromString(s"${baseUri}instances")
          val request = Request[IO](method = Method.GET, uri = uri)

          http.expect(request)(jsonOf[IO, List[String]])
        }
      }
    }
}