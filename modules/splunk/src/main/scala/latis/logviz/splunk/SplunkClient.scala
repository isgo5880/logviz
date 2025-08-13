package latis.logviz.splunk

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString

import latis.logviz.model.Event

trait SplunkClient {
  def query(/* takes args in the future */): Stream[IO, Event]
}

object SplunkClient {
  def make(splunkuri: Uri, username: String, password: String): Resource[IO, SplunkClient] = {
    EmberClientBuilder
      .default[IO]
      .build
      .map { client => 
        new SplunkClient {
          private def getSessionKey: IO[String] = {
            val request = Request[IO](
              method = Method.POST,
              uri = splunkuri / "services" / "auth" / "login"
            ).withEntity(
              UrlForm(
                "username" -> username,
                "password" -> password
              )
            )

            client.expect[String](request).map{response =>
              val split = response.split("sessionKey")
              val sessionkey: String = split(1).split(">")(1).dropRight(2)
              sessionkey
            }
          }

          private def generateQuery(sessionkey: String, query: String): IO[String] = {
            val request = Request[IO](
              method = Method.POST,
              uri = splunkuri / "services" / "search" / "jobs"
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            ).withEntity(
              UrlForm(
                "search" -> query
              )
            )

            client.expect[String](request).map{response =>
              val sid: String = response.split("sid")(1).drop(1).dropRight(2)
              sid
            }
          }

          private def checkQuery(sessionkey: String, sid: String): IO[Int] = {
            val request = Request[IO](
              method = Method.GET,
              uri = splunkuri / "services" / "search" / "jobs" / sid
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            )

            client.expect[String](request).map{response =>
              // dispatchState and isFailed may also be useful
              val done: Int = response.split("isDone")(1).split("isEventsPreviewEnabled")(0).split("<")(0).drop(2).toInt
              done
            }
          }

          private def waitLoop(sessionkey: String, sid: String): IO[Unit] = {
            for {
              status <- checkQuery(sessionkey, sid)
              _      <- if (status == 1) IO.unit
                        else IO.sleep(2.seconds) *> waitLoop(sessionkey, sid)
            } yield ()
          }

          private def getTotalResults(sessionkey: String, sid: String): IO[Int] = {
            val request = Request[IO](
              method = Method.GET,
              uri = splunkuri / "services" / "search" / "jobs" / sid
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            )

            client.expect[String](request).map{response =>
              val total: Int = response.split("resultCount")(1).split("resultIsStreaming")(0).split("<")(0).drop(2).toInt
              total
            }
          }

          private def getResults(sessionkey: String, sid: String, total: Int): IO[Json] = {
            val request = Request[IO](
              method = Method.GET,
              uri = (splunkuri / "services" / "search" / "jobs" / sid / "events") // use /results if we want transformed events (performing stats or operations on events)
                .withQueryParam("output_mode", "json")
                .withQueryParam("offset", "0") // index of the first result to return
                .withQueryParam("count", s"$total") // maximum number of results to return
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            ) 

            client.expect[Json](request)
          }

          private def makeStream(response: Json): Stream[IO, Event] = { 
            // making a stream of messages
            val decodedResult = response.hcursor.downField("results").as[List[SplunkMessage]]
            val mStrm: Stream[IO, SplunkMessage] = decodedResult match {
              case Right(messages) => 
                Stream.emits(messages).covary[IO]
              case Left(error) =>
                Stream.raiseError(new Exception(s"Error parsing Json into Stream with error: $error"))
            }

            val getEvent: SplunkMessage => Option[Event] = (m: SplunkMessage) => {
              val line: String = m.hcursor.downField("line").as[String].getOrElse("unknown")

              // using contains method -- is there a better way to do this?
              if line.contains("HTTP/1.1 GET") then
                val spl = line.split("HTTP/1.1 GET")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val request = line.split("HTTP/1.1 GET")(1).drop(1)
                Some(Event.Request(id, time, request))
              else if line.contains("HTTP/1.1 ") then
                val spl = line.split("HTTP/1.1 ")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val status = spl(1).split(" OK")(0).toInt
                Some(Event.Response(id, time, status))
              else if line.contains("Elapsed ") then
                val spl = line.split("Elapsed ")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val duration = spl(1).split("source")(0).drop(6).toInt
                Some(Event.Success(id, time, duration))
              else if line.contains("Ember-Server service bound to address:") then
                val time = line.split(" INFO")(0).drop(1)
                Some(Event.Start(time))
              else if line.contains("Request failed") then
                val spl = line.split(" ERROR")
                val id = "unknown"
                val time = spl(0).drop(1)
                val msg = "unknown"
                Some(Event.Failure(id, time, msg))
              else
                None
            }

            // stream of messages -> stream of events
            val eStrm: Stream[IO, Event] = mStrm.map(getEvent).unNone
            eStrm
          }

          def query(/* takes args in the future */): Stream[IO, Event] = {
            // make the query from args
            val query = "search index=latis source=latis3-swp* earliest_time=-24h@h latest_time=now"

            Stream.eval {
              for {
                sessionkey <- getSessionKey// Step 1: Get a session key
                sid        <- generateQuery(sessionkey, query) // Step 2: Generate a query
                _          <- waitLoop(sessionkey, sid) // Step 3: Check the status of a query
                total      <- getTotalResults(sessionkey, sid)
                _          <- IO.println(s"Parsing $total results...")
                res        <- getResults(sessionkey, sid, total) // Step 4: Get the results from the query
              } yield res
            }.flatMap(makeStream) // Step 5: Make a stream of logs and get a stream of events
          }
        }
      }
  }
}
