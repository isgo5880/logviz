package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import fs2.Stream

import latis.logviz.model.Event

/** Describes an event source to be used for grabbing events
  * 
  * Classes that extend this trait must have a getEvents(start, end) method
  * which returns a stream of events. Known classes that extend this trait
  * are JSONEventSource and SplunkEventSource.
  */
trait EventSource {
  /** Gets events from within a given timeframe
    *
    * @param start the start time used to filter for events
    * @param end the end time used to filter for events
    * @return a stream of events that have been parsed into Event objects
    */
  def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event]
}