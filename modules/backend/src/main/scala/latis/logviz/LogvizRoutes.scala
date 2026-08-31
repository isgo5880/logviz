package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.EventStream
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder

import latis.logviz.model.Event

/** 
 * Defines Routes
 * 
 * Following routes are created:
 * - GET / (index.html)
 * - GET /main.js
 * - GET /events.json 
 * - GET /events
 * 
 * Get each file from the resources folder, else return notFound
*/
class LogvizRoutes(eventsource: EventSource, instancesource: InstanceSource) extends Http4sDsl[IO] {
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root =>
      StaticFile.fromResource("index.html", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "main.js" =>
      StaticFile.fromResource("main.js", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "events.json" =>
      StaticFile.fromResource("events.json", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "events1.json" =>
      StaticFile.fromResource("events1.json", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "styles.css" =>
      StaticFile.fromResource("styles.css", req.some).getOrElseF(NotFound())

    // event with query params
    case GET -> Root / "events" :? 
    StartDateQueryParamMatcher(startTime) +&
    EndDateQueryParamMatcher(endTime) +&
    InstanceQueryParamMatcher(instance) =>

      val start = startTime.toEither.leftMap(
        _ => BadRequest("invalid start date")
      )

      val end = endTime.toEither.leftMap(
        _  => BadRequest("invalid end date"),
      )
      
      //only if both start and end are valid(Right)
      //we end up with same type on Either[IO[Response[IO]], IO[Response[IO]]
      //so we can then just merge to IO[Response[IO]]
      (start, end).mapN { (start, end) => 
        val events = eventsource.getEvents(start, end, instance)
        val sse: EventStream[IO] = events.map(eventToServerSent)
        Ok(sse)
      }.merge
    
    //default (past 24 hours)
    case GET -> Root / "events" =>
      // hard coding times for now
      val end: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
      val start: LocalDateTime = end.minusHours(24)
      val events = eventsource.getEvents(start, end, None)
      val sse: EventStream[IO] = events.map(eventToServerSent)

      Ok(sse)

    case GET -> Root / "instances" =>
      val list = instancesource.instances.map { tup =>
        tup.asJson
      }

      Ok(list)
  }

  private def eventToServerSent(event: Event): ServerSentEvent = {
    val json = event.asJson
    ServerSentEvent(json.noSpaces.some, json.asObject.flatMap(_("eventType").map(_.noSpaces)))
  }

  implicit val dateQueryParamDecoder: QueryParamDecoder[LocalDateTime] =
    //https://www.youtube.com/watch?v=v_gv6LsWdT0 36:20 Rock the JVM
    QueryParamDecoder[String].emap { dateString => 
      Either.catchNonFatal(LocalDateTime.parse(dateString)).leftMap { e =>
        ParseFailure(e.getMessage, e.getMessage)
      }
    }

  object StartDateQueryParamMatcher extends ValidatingQueryParamDecoderMatcher[LocalDateTime]("startTime")
  object EndDateQueryParamMatcher extends ValidatingQueryParamDecoderMatcher[LocalDateTime]("endTime")
  object InstanceQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("instance")
  
}