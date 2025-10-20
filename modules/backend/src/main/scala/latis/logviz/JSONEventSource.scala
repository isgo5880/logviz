package latis.logviz

import java.time.format.DateTimeParseException
import java.time.LocalDateTime

import cats.effect.IO
import cats.syntax.all.*
import fs2.data.json.ast
import fs2.data.json.circe.*
import fs2.data.text.utf8.byteStreamCharLike
import fs2.io.readClassLoaderResource
import fs2.Stream

import latis.logviz.model.Event

val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

/** A JSON event source that reads events from a JSON file */
class JSONEventSource extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] =

    val byteStream: Stream[IO, Byte] = readClassLoaderResource[IO]("events.json")
    
    val decodedJson: Stream[IO, Event] = byteStream
      .through(ast.parse)
      .flatMap{ j => 
        j.as[List[Event]] match {
          case Right(events) => Stream.emits(events)
          case Left(error) => 
            Stream.raiseError[IO](new Exception(s"Decoding events.json failed. error: $error"))
        }
      }.filter{
        case Event.Start(time) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Request(id, time, request) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Response(id, time, status) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Success(id, time, duration) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Failure(id, time, msg) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
      }
    decodedJson
}