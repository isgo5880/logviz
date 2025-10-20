package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.splunk.*

/** A Splunk event source that reads events from Splunk
 * 
 * @constructor create a new splunk source with a splunk client
 * @param sclient the emberClient used to access splunk
 */
class SplunkEventSource(sclient: SplunkClient) extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] = {
    sclient.query(start, end)
  }
}