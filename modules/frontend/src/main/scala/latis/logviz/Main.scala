package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import calico.IOWebApp
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import cats.effect.kernel.Ref
import fs2.concurrent.SignallingRef
import fs2.concurrent.Signal
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client

import latis.logviz.model.Event
import cats.effect.std.Supervisor


/**
 * Renders HTML elements for log events using EventClient and EventComponent
 * 
 * set up the layout for logviz page 
 * passing stream of events from eventclient to EventComponent to parse, store and draw onto canvas
 * */ 
object Main extends IOWebApp {

  override def render: Resource[IO, HtmlElement[IO]] = {
    val http: Client[IO] = FetchClientBuilder[IO].create

    val client: Resource[IO, EventClient] = Resource.eval(EventClient.fromHttpClient(http))

    for {
      ec          <- client
      eventRef    <- Resource.eval(SignallingRef[IO].of[Option[EventDetails]](None))
      now         <- Resource.eval(IO(LocalDateTime.now(ZoneOffset.UTC)))
      startRef    <- Resource.eval(SignallingRef[IO].of(now.minusHours(24)))
      endRef      <- Resource.eval(SignallingRef[IO].of(now))
      pickedStart <- Resource.eval(SignallingRef[IO].of(now.minusHours(24)))
      pickedEnd   <- Resource.eval(SignallingRef[IO].of(now))
      liveRef     <- Resource.eval(SignallingRef[IO].of(false))
      events      <- Resource.eval(SignallingRef[IO].of[Stream[IO, Event]](Stream.empty))
      instanceRef <- Resource.eval(SignallingRef[IO].of[String](""))

      // liveButton  <- button( 
      //                 `type` := "button",
      //                 "LIVE",
      //                 styleAttr <-- liveRef.map{
      //                                 case true => "background-color: #db2a30"
      //                                 case false => "background-color: #FFFFFF"
      //                 },
      //                 onClick(_ => {
      //                   for {
      //                     //TODO "disabling/greying out" endtime button when live so visually looks like its not being used?
      //                     live  <- liveRef.updateAndGet(bool => !bool)

      //                     _     <- if (!live) {
      //                                 //going from live to not live: visually it'll look like the canvas stopped moving
      //                                 //so endtime should just be where we left off
      //                                 endRef.update(_ => LocalDateTime.now(ZoneOffset.UTC))
                                      
      //                               } else {
      //                                 IO.unit
      //                               }

      //                     // start <- startRef.get

      //                     //if I just have params take liveref, then changes to liveref should trigger a change right?
      //                     // _     <- if (live) {
      //                     //             startRef.set(start)
      //                     //             // events.set(ec.getEvents(start.toString()))
      //                     //           } else {
      //                     //             //updating endRef already calls getevents correctly, so I dont think I need it again
      //                     //             //events.set(ec.getEvents(start.toString(), end.toString()))
      //                     //             IO.unit
      //                     //           }
                          
      //                   } yield ()
      //                 })
      //               )

      updateSource<- button(
                        `type` := "button",
                        "Load Events",
                        onClick(_ =>
                          for {
                            start <- startRef.get
                            end   <- endRef.get
                            _     <- liveRef.set(false)
                            _     <- pickedStart.set(start)
                            _     <- pickedEnd.set(end)
                          } yield ()
                        )
                      )
      
      sup         <- Supervisor[IO](await=true)

      //any changes to any of these signallingrefs should trigger a getevents call
      params      = liveRef.asInstanceOf[Signal[IO, Boolean]]

      _           <- Resource.eval(sup.supervise(params.discrete.drop(1).switchMap { _ => 
                        Stream.exec(
                          for {
                            s      <- startRef.get
                            e      <- endRef.get
                            i      <- instanceRef.get
                            strmIO <- events.set(ec.getEvents(s.toString(), e.toString(), i))
                          } yield strmIO)
                  }.compile.drain).void)

      //probably don't need this anymore since we're combining signal in params
      // _           <- Resource.eval(sup.supervise(endRef.discrete.evalMap{end => 
      //                 //if an end time if given, then no longer expecting live stream of events
      //                 liveRef.set(false) >> startRef.get.flatMap { start =>
      //                   events.set(ec.getEvents(start.toString(), end.toString()))
      //                 }
      //               }.compile.drain).void)

      // _           <- Resource.eval(sup.supervise(startRef.discrete.evalMap{t => 
      //                   for {
      //                     live  <- liveRef.get
      //                     start = t.toString

      //                     _     <- if (live) {
      //                               events.set(ec.getEvents(start))
      //                             } else {
      //                               endRef.get.flatMap(end => events.set(ec.getEvents(start, end.toString())))
      //                             }

      //                   } yield()
      //                 }.compile.drain).void)

      timeRange   <- new TimeRangeComponent(startRef, endRef).render
      timeSelect  <- div(idAttr:= "time-selection", timeRange, updateSource)  

      zoomRef     <- Resource.eval(Ref[IO].of(1.0))
      zoom        <- new ZoomComponent(zoomRef).render

      evComponent =  new EventDetailComponent(eventRef)
      info        <- evComponent.render
      component   =  new EventComponent(events, eventRef, pickedStart, pickedEnd, liveRef, zoomRef)
      timeline    <- component.render
      
      instances   <- Resource.eval(ec.getInstances)
      autocomplete<- new AutocompleteComponent(instances, instanceRef).render

      requestInfo <- div(idAttr:= "request-detail", info)
      box         <- div(idAttr:= "box", timeline, zoom, requestInfo, timeSelect, autocomplete)
      html        <- div(idAttr:= "container", box)
      
  } yield html
}
 
}
