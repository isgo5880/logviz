package latis.logviz

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.PQueue
import cats.syntax.all.*

import latis.logviz.model.RequestEvent
import latis.logviz.model.Event

trait EventParser {
  def parse(event: Event): IO[Unit]
  def getEvents(): IO[List[(RequestEvent, Int)]]
  def getMaxConcurrent(): IO[Int]
  def getUnusedColumn(pq: PQueue[IO, Column], acc: List[Column] = Nil): IO[Option[Column]]
}

/**
 * Parsing events as request events and storing them as incomplete and complete to be used to create rectangles
 * Keeping track of the maximum number of concurrent events at a time to determine number of columns to draw
*/
object EventParser {
  def apply(startTime: LocalDateTime): IO[EventParser] = {
    for {
      //ongoing request events mapped to concurrent depth
      eventsRef 		<- Ref[IO].of(Map[String, (RequestEvent, Column)]())
      //completed events(with a finished time) mapped to concurrent depth
      compEventsRef <- Ref[IO].of(List[(RequestEvent, Column)]())
      //counter for number of concurrent events 
      colCounter 		<- Ref[IO].of(0)
      //max number of concurrent events at once
      maxCounter 		<- Ref[IO].of(1)
      //priority queue to keep track of which concurrency depth to tie a request event to
      //resource on pq: https://typelevel.org/cats-effect/docs/std/pqueue
      pq 						<- PQueue.bounded[IO, Column](100)
      _ 						<- (0 to 99).toList.traverse(i => pq.offer(Column(i, false)))
      // _ 						<- (0 to 99).toList.traverse(pq.offer(_))
    } yield new EventParser {

      /**
       * Parsing though log events given from stream and mapping/appending (in)complete events
       * 
       * Removing the mapping once the event is completed and keeping track of number of concurrent events at once. 
       */
      override def parse(event: Event): IO[Unit] =
        event match {
          //start events have no id and is waiting on no other events, so will just append to list of completed events
          case Event.Start(time) =>
            for {
              cDepth	<- pq.take
              //can set to true later after if care about keeping track of the first event of that column
              usedCol =  cDepth.copy(used = true)
              c				<- colCounter.updateAndGet(c => c + 1)
              _ 			<- maxCounter.update(prev => math.max(prev, c))
              _ 			<- compEventsRef.update(lst => 
                          (RequestEvent.Server(time), usedCol) +: lst
                        )
              _				<- colCounter.update(c => c - 1)
              _				<- pq.offer(usedCol)
            } yield()

          //map as incomplete event since need to wait on additional log events with corresponding id
          case Event.Request(id, time, request) => 
            for {
              cDepth  <- pq.take
              usedCol =  cDepth.copy(used = true)
              c       <- colCounter.updateAndGet(c => c + 1)
              _       <- maxCounter.update(prev => math.max(prev, c))
              _       <- eventsRef.update(map =>
                          map + (id -> (RequestEvent.Request(time, request), usedCol))
                        )
            } yield ()

          //becomes completed event if status is an error-> no longer waiting on any additional log events
          //else wait for log events with corresponding ids
          case Event.Response(id, time, status) => 
            if (status >= 400) {
              for {
                map     <- eventsRef.get
                cDepth  <- map.get(id) match {
                            //Found the request with corresponding id!
                            case Some((RequestEvent.Request(start, url), currDepth)) =>
                              compEventsRef.update(lst => 
                                (RequestEvent.Failure(start, url, time, status.toString),
                                currDepth) +: lst)  
                              >> IO(currDepth)
                            
                            //Odd error that shouldn't happen, would need to investigate
                            //offer column back to priority queue since we will not work with this "bad" event anymore
                            case Some((_, currDepth)) => 
                              colCounter.update(c => c - 1) >>
                              pq.offer(currDepth) >>
                              (IO.raiseError(new Exception(
                              "Got an event that is not request, something is wrong")))
                        
                            //No request event found, meaning that it was sometime outside of time range. 
                            //Thus, create a completed partial event since error status
                            case None =>
                              for {
                                currDepth <- getUnusedColumn(pq).flatMap{
                                              case Some(value) => IO(value)
                                              case None => IO.raiseError(new Exception(
                                                "BADD!! No unused column available. Increase number of columns!!!"))
                                            }
                                c         <- colCounter.updateAndGet(c => c + 1)
                                _         <- maxCounter.update(prev => math.max(prev, c))
                                _         <- compEventsRef.update(lst => 
                                              (RequestEvent.Partial(time, status.toString()),
                                              currDepth) +: lst)
                              } yield(currDepth)

                          }
                _       <- eventsRef.update(m => m - id)
                _       <- colCounter.update(c => c - 1)
                _       <- pq.offer(cDepth)
              } yield ()
            } else {

              for {
                map <- eventsRef.get
                _   <- map.get(id) match {
                        //Found request event with corresponding id-> good no need to do anything and just wait for next log event with same id
                        case Some(_) => IO.unit

                        //Request event for this id outside of time range in the past. Store as incomplete partial event since success status code. 
                        case None =>
                          for {
                            cDepth  <- getUnusedColumn(pq).flatMap{
                                        case Some(value) => IO(value)
                                        case None => IO.raiseError(new Exception(
                                          "No unused column available. Increase number of columns!!!"))
                                      }
                            c       <- colCounter.updateAndGet(c => c + 1)
                            _       <- maxCounter.update(prev => math.max(prev, c))
                            _       <- eventsRef.update(map =>
                                        map + (id -> (RequestEvent.Partial(time, status.toString()), cDepth))
                                      )
                          } yield ()
                      }
              } yield()

            }

          //Successful end to a request-> store as completed event
          case Event.Success(id, time, duration) => 
            for {
              map     <- eventsRef.get
              dResult <- map.get(id) match {
                          case Some((RequestEvent.Request(start, url), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Success(start, url, time, duration), 
                              currDepth) +: lst)
                            >> IO(currDepth)

                          case Some((RequestEvent.Partial(start, status), currDepth)) =>
                            val successTime = LocalDateTime.parse(time)
                            // if we should have had a partial request event and we don't, discard, otherwise update the partial event 
                            val eventStart = successTime.minus(duration, ChronoUnit.MILLIS)
                            if (startTime.isAfter(eventStart)) {
                              // a valid partial event, update it
                              compEventsRef.update(lst =>
                                (RequestEvent.Partial(time, s"successful event with response status of: $status, duration: $duration"), 
                                currDepth) +: lst)
                              >> IO(currDepth)
                            } else {
                              // we should have had a partial request event and we don't
                              IO(currDepth.copy(used = false))
                            }

                          case Some((_, currDepth)) => 
                            colCounter.update(c => c - 1) >>
                            pq.offer(currDepth) >>
                            IO.raiseError(new Exception(
                            "Got an event that is not request or partial, something is wrong"))

                          // request and success response somewhere in the past outside of date range
                          // thus, add a completed partial event
                          case None => 
                            for {
                              currDepth <- getUnusedColumn(pq).flatMap{
                                            case Some(value) => IO(value)
                                            case None => IO.raiseError(new Exception(
                                              "No unused column available. Increase number of columns!!!"))
                                          }
                              c         <- colCounter.updateAndGet(c => c + 1)
                              _         <- maxCounter.update(prev => math.max(prev, currDepth.number + 1)) // maxCounter is 1-indexed
                              _         <- compEventsRef.update(lst => 
                                            (RequestEvent.Partial(time, s"partial success event- start time unknown. duration: $duration"), 
                                            currDepth) +: lst)
                            } yield(currDepth)
                        }
              _       <- eventsRef.update(m => m - id)
              _       <- colCounter.update(c => c - 1)
              _       <- pq.offer(dResult)
            } yield ()

          //same format as success
          case Event.Failure(id, time, msg) => 
            for {
              map     <- eventsRef.get
              cDepth  <- map.get(id) match {
                          case Some((RequestEvent.Request(start, url), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Failure(start, url, time, msg), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some((RequestEvent.Partial(start, status), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Partial(time, s"failed event with response status of: $status, error msg: $msg"), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some((_, currDepth)) => 
                            colCounter.update(c => c - 1) >>
                            pq.offer(currDepth) >>
                            IO.raiseError(new Exception(
                            "Got an event that is not request or partial, something is wrong"))
                          case None =>
                            for {
                              currDepth <- getUnusedColumn(pq).flatMap{
                                            case Some(value) => IO(value)
                                            case None => IO.raiseError(new Exception(
                                              "No unused column available. Increase number of columns!!!"))
                                          }
                              c         <- colCounter.updateAndGet(c => c + 1)
                              _         <- maxCounter.update(prev => math.max(prev, c))
                              _         <- compEventsRef.update(lst => 
                                            (RequestEvent.Partial(time, s"partial failure event- start time unknown. error msg: $msg"), 
                                            currDepth) +: lst)
                            } yield(currDepth)
                        }
              _       <- eventsRef.update(m => m - id)
              _       <- colCounter.update(c => c - 1)
              _       <- pq.offer(cDepth)
            } yield ()
        }
    
      /**
        * Combining ongoing and completed events together in a list
        *
        * @return list of pairs of request events and their concurreny depth level!
        */
      override def getEvents(): IO[List[(RequestEvent, Int)]] =
        for {
          incomp      <- eventsRef.get
          events      <- IO(incomp.foldLeft(List[(RequestEvent, Int)]()){ (acc, map) =>
                          map match
                            case (_, (event, cDepth)) => (event, cDepth.number) :: acc
                        })
          compEvents  <- compEventsRef.get
          comp        =  compEvents.map((event, col) => (event, col.number))
        } yield(comp ++ events)

      override def getMaxConcurrent(): IO[Int] = 
        maxCounter.get

      /**
        * Gets an unused column from the pq
        * 
        * Partial events cannot be assigned an already used column or else
        * they will overlap over the existing events in that column because they 
        * extend all the way to the start of the canvas. 
        * 
        * Thus, given a partial event, it must be the first event drawn on the column it's assigned
        *
        * @param pq
        * @param acc used to store the columns that were already used, pushed back into PQ once found an unused column
        * @return returns an Option, which will either be an unused column or none. None meaning that there's 
        * no more unused columns available in the PQ which indicates that we need to increase the size of the PQ(more total columns)
        */
      override def getUnusedColumn(pq: PQueue[IO, Column], acc: List[Column]): IO[Option[Column]] = 
        pq.tryTake.flatMap{
          case Some(col) =>  
            if (col.used) {
              getUnusedColumn(pq, col :: acc)
            } else {
                acc.traverse(pq.offer(_))
                >> IO(Some(col.copy(used = true)))
            }
          case None =>
            //pq is empty: meaning no columns that are unused -> need to increase max number of columns
            acc.traverse(pq.offer(_))
            >> IO(None)
        }
    }
  }
}