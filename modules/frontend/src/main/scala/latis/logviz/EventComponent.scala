package latis.logviz

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

import org.scalajs.dom
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.MouseEvent
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import cats.effect.std.Dispatcher
import cats.effect.kernel.Ref
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.dom.HtmlElement
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.model.Rectangle
import latis.logviz.model.RequestEvent
import cats.effect.std.Supervisor



/**
  * Creates canvas components and draws log events
  * 
  * @param stream stream of log events from EventClient
  * @param requestDetails div for hover feature
  * @param startTime start time of date range
  * @param endTime end time of date range
  * @param liveRef whether live button is toggled
  */
class EventComponent(
  signal: Signal[IO, Stream[IO, Event]],
  eventRef: Ref[IO, Option[EventDetails]],
  startTime: SignallingRef[IO, LocalDateTime],
  endTime: SignallingRef[IO, LocalDateTime],
  liveRef: SignallingRef[IO, Boolean],
  zoomRef: Ref[IO, Double]
  ) {
  val timestampFormatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00")

 
  /**
    * returns scrollable canvas component
    */
  def render: Resource[IO, HtmlElement[IO]] = 
    
    for {
      sup           <- Supervisor[IO](await=true)
      canvasIO      <- canvasTag(idAttr:= "canvas")
      sizer         <- div(idAttr:= "sizer")
      timeline      <- div(idAttr:= "timeline", canvasIO, sizer)
      canvas        =  canvasIO.asInstanceOf[HTMLCanvasElement]
      context       =  canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      eParser       <- Resource.eval(startTime.get.flatMap(EventParser(_)))
      parserRef     <- Resource.eval(Ref[IO].of(eParser))
      parserUpdated <- Resource.eval(Ref[IO].of(false))
      alertRef      <- Resource.eval(Ref[IO].of(true))
      _             <- Resource.eval(sup.supervise(signal.discrete.drop(1).switchMap { eventStream => 
                        // making a new parser
                        val newParser = for {
                          start  <- startTime.get
                          parser <- EventParser(start)
                          _      <- parserRef.set(parser)
                          _      <- alertRef.set(true)
                        } yield parser

                        val parseEvents = Stream.eval(newParser).flatMap { parser =>
                          eventStream.evalTap { event =>
                            alertRef.set(false) >>
                            parser.parse(event).handleErrorWith(IO.println) >> parserUpdated.set(true)
                          }
                        } 

                        val alert = alertRef.get.flatMap { empty =>
                          IO {
                            dom.window.alert("No events returned from query.")
                          }.whenA(empty)
                        }

                        parseEvents ++ Stream.eval(alert)
                      }.compile.drain).void)
      scrollRef     <- Resource.eval(Ref[IO].of(0.0))
      isTop         <- Resource.eval(Ref[IO].of(true))
      rectRef       <- Resource.eval(Ref[IO].of(List[Rectangle]()))
      end           <- Resource.eval(endTime.get)
      //prevEndRef    <- Resource.eval(Ref[IO].of(end))
      prevZoomRef   <- Resource.eval(Ref[IO].of(1.0))
        _           <- animate(canvas, 
                        context, 
                        sizer.asInstanceOf[HTMLElement],
                        parserRef,
                        scrollRef, 
                        isTop, 
                        rectRef,
                        prevZoomRef,
                        parserUpdated)
      _             <- hover(canvas, eventRef, rectRef)
    } yield(timeline)
  
  /**
   * Conversion to get position of a timestamp on the canvas
   * 
   * Find the difference in seconds between the timestamp we're interested in and the current time
   * Multiply by abitrary pixels per second to get y position on the canvas to start drawing on. 
   * Same as saying how far off we are from the top of canvas(current time)
   * [[https://www.geeksforgeeks.org/java/java-time-duration-class-in-java/#]]
  */
  private def convertTime(timestamp: LocalDateTime, current: LocalDateTime, pixelsPerSec: Double): Double = 
    java.time.Duration.between(timestamp, current).toSeconds() * pixelsPerSec

  /**
   * (Re)Drawing canvas timestamp ticks and event rectangles
   * 
   * Truncate current time to minutes. Needed for drawing only 0 second timestamps
   * Resource for truncate: [[https://www.geeksforgeeks.org/java/localtime-truncatedto-method-in-java-with-examples/]]
   * Got difference in minutes between current time and endtime to determine number of timestamp drawings
   * 
   * For every minute: calculate its position and draw that timestamp onto canvas
   * Draw every rectangle event from list of rectangles
   * 
   * @param current current time to be used for convertTime function
   * @param endTime the end of range of time that user may want to see
   * @param canvas
   * @param context 
   * @param rects list of rectangles to draw
   * @param cols number of columns to draw
   * @param top   current scroll position from the top
   * @param width total available space for columns to be drawn
  */
  private def drawCanvas(
    end: LocalDateTime,
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    rects: List[Rectangle],
    cols: Int,
    top: Double,
    width: Int,
    pixelsPerSec: Double
  ): IO[Unit] =

    //computes what the top of the canvas(viewport) is
    //takes the end date range and subtracts from the scroll position to 
    //know what the timestamp is at the top of the viewport
    val topTS = end.minusSeconds((top / pixelsPerSec).toLong)
    val currTruncated = topTS.truncatedTo(ChronoUnit.MINUTES)
    
    //calculates the number of minutes/timestamps to be drawn
    //two scenarios:
    //  -full canvas viewport of minutes to be drawn
    //  -as we near the bottom towards the start time, our viewport of minutes to be drawn should decrease(less minutes to draw)
    // val mins = ((math.min(convertTime(start, topTS), canvas.height.toDouble) 
    //             /pixelsPerSec / 60)).toInt

    //dont think we actually need the above anymore if we shouldnt be able to scroll past the bottom of the start time anyway. 
    //number of pixels a viewport is (size of canvas height) / number of pixels per second 
    //gives us the total number of seconds within a viewport / 60 to give us minutes
    val mins = (canvas.height.toDouble / pixelsPerSec / 60).toInt

    //used for alternating column colors
    val colors = List("lightgray", "white")

    for {
      _ <- IO(context.clearRect(0,0,canvas.width, canvas.height))
      _ <- (0 until cols).toList
            .traverse { col => 
              val colWidth = width / cols
              val x = 150 + colWidth * col
              val color = colors(col % 2)
              drawRect(context, x, 0, colWidth, canvas.height, color)
            }
      _ <- (0 to mins).toList
            .traverse{ min =>
              val timestamp = currTruncated.minusMinutes(min)
              val y = convertTime(timestamp, topTS, pixelsPerSec).toDouble
              drawTS(context, y, timestamp)
            }
      _ <- rects.traverse{
            case Rectangle(event, x, y, width, height, color) => 
              drawRect(context, x, y, width, height, color)
            }
    } yield()


  private def drawRect(
    context: dom.CanvasRenderingContext2D,
    x: Double,
    y: Double,
    width: Double,
    duration: Double,
    color: String
  ): IO[Unit] = IO {
    context.fillStyle = color
    context.fillRect(x, y, width, duration)

    // put borders around events
    if(color != "lightgray" && color != "white") then
      context.lineWidth = 0.3
      context.strokeStyle = "black"
      context.strokeRect(x, y, width, duration)
  }
  
  private def drawTS(
    context: dom.CanvasRenderingContext2D,
    y: Double,
    timestamp: LocalDateTime
    ): IO[Unit] = IO {
    context.font = ("helvetica")
    context.fillStyle = "black"
    context.fillText(timestamp.format(timestampFormatter), 0, y)}

  /**
   * Animate canvas on the browser
   * 
   * (Re) creates list of rectangles to be drawn on canvas at each animation frame
   * 
   * Used dispatcher utility to use requestAnimationFrame as it takes in a js callback function. 
   * Dispatcher resource: [[https://typelevel.org/cats-effect/docs/std/dispatcher]]
   * 
   * @param canvas
   * @param context
   * @param sizer div for controlling how much space we have to scroll
   * @param parser event parser holding list of events
   * @param prevScrollPos ref holding the previous scroll position
   * @param isLive ref determining whether to update live or not
   * @param rectRef ref to hold list of rectangles to be drawn
   * @param prevZoomRef ref to hold previous zoom level
   * @param parserUpdated ref determining whether there has been a new event parsed
  */
  private def animate(
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    sizer: HTMLElement,
    parserRef: Ref[IO, EventParser],
    prevScrollPos: Ref[IO, Double],
    isTop: Ref[IO, Boolean],
    rectRef: Ref[IO, List[Rectangle]],
    prevZoomRef: Ref[IO, Double],
    parserUpdated: Ref[IO, Boolean]
  ): Resource[IO, Dispatcher[IO]] =
    Dispatcher.sequential[IO] evalTap{ dispatcher => 

      def go(timestamp: Double): IO[Unit] = 
        for {
          scroll      <- IO(canvas.parentElement.scrollTop)
          top         <- isTop.get
          prevScroll  <- prevScrollPos.get
          start       <- startTime.get
          //prevEnd     <- prevEndRef.get
          end         <- endTime.get
          live        <- liveRef.get
          prevZoom    <- prevZoomRef.get
          zoomLev     <- zoomRef.get
          parser      <- parserRef.get
          newParser   <- parserUpdated.get

          //if live, then possible scenarios: 
          //  - at top of canvas so continuously redrawing each animation frame
          //  - draw when scroll or zoom
          //  - changing start date -> new stream of events -> redraw
          _       <- if (live && (top || prevScroll != scroll || prevZoom != zoomLev || newParser)) {

                      for {
                        //if user changes size of browser or anything, then just adjusting canvas to fit those changes
                        tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                        height  =  tlRect.height
                        _       <- IO(canvas.width = tlRect.width.toInt)
                        _       <- IO(canvas.height = tlRect.height.toInt)

                        //width to be used for drawing area
                        //subtracting by 150 to make room for timestamps to be drawn. So the total width available to event/column drawing is 150 pixels less
                        width   <- IO(canvas.width - 150) 

                        //grabbing the current time to be used as endtime/top of canvas (since live)
                        endTime <- IO(LocalDateTime.now(ZoneOffset.UTC))

                        //updating sizer height used for how much you can scroll
                        _       <- IO(sizer.style.height = s"${convertTime(start, endTime, zoomLev)-height}px")

                        //if zoom level has changed, then need to compute the new scroll position so that user's view is around the same event
                        newTop  <- if (prevZoom != zoomLev) {
                                    updateScrollTop(canvas, scroll, height/2, zoomLev/prevZoom)
                                  } else {
                                    IO(scroll)
                                  }
                        maxCol  <- parser.getMaxConcurrent()
                        events  <- parser.getEvents()
                        rects   = Rectangles.makeRectangles(endTime,
                                   height,
                                   newTop,
                                   width/maxCol,
                                   events,
                                   start,
                                   zoomLev)
                        _       <- rectRef.update(_ => rects)
                        _       <- drawCanvas(endTime,
                                    canvas,
                                    context,
                                    rects,
                                    maxCol,
                                    newTop,
                                    width,
                                    zoomLev)
                        _       <- prevScrollPos.update(_ => newTop)
                        _       <- if (newTop == 0.0) {
                                    isTop.update(_ => true)
                                  } else {
                                    isTop.update(_ => false)
                                  }
                        _       <- prevZoomRef.set(zoomLev)
                        _       <- parserUpdated.set(false)
                      } yield ()

                      //only redraw when scrolled, zoomed, or new stream of events(changing start or end time)
                      //don't think I need prevEnd anymore with the introduction of newParser
                      //prevEnd != end || prevScroll != scroll || prevZoom != zoomLev
                    } else if (!live && (prevScroll != scroll || prevZoom != zoomLev || newParser)){
                        for {
                          tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                          height  =  tlRect.height
                          _       <- IO(canvas.width = tlRect.width.toInt)
                          _       <- IO(canvas.height = tlRect.height.toInt)
                          width   <- IO(canvas.width - 150)
                          _       <- IO(sizer.style.height = s"${convertTime(start, end, zoomLev)-height}px")
                          newTop  <- if (prevZoom != zoomLev) {
                                    updateScrollTop(canvas, scroll, height/2, zoomLev/prevZoom)
                                  } else {
                                    IO(scroll)
                                  }
                          maxCol  <- parser.getMaxConcurrent()
                          events  <- parser.getEvents()
                          rects   = Rectangles.makeRectangles(end,
                                    height,
                                    newTop,
                                    width/maxCol,
                                    events,
                                    start,
                                    zoomLev)
                          _       <- rectRef.update(_ => rects)
                          _       <- drawCanvas(end,
                                      canvas,
                                      context,
                                      rects,
                                      maxCol,
                                      newTop,
                                      width,
                                      zoomLev)
                          _       <- prevScrollPos.update(_ => newTop)
                          //_       <- prevEndRef.set(end)
                          _       <- prevZoomRef.set(zoomLev)
                          _       <- parserUpdated.set(false)
                        } yield()
                    } else {
                      IO.unit
                    }
          _       <- IO.delay(dom.window.requestAnimationFrame(ts => 
                      dispatcher.unsafeRunAndForget(go(ts))))
        } yield()

      IO.delay(dom.window.requestAnimationFrame(ts => 
        dispatcher.unsafeRunAndForget(go(ts))))
      }


  /** 
  * Displays event information on hover over an event rectangle
  *
  * @param canvas
  * @param requestDetails HTML element to show event details of the event hovered over
  * @param rectRef
  */
  private def hover(
    canvas: HTMLCanvasElement,
    eventRef: Ref[IO, Option[EventDetails]],
    rectRef: Ref[IO, List[Rectangle]]
    ): Resource[IO, Dispatcher[IO]]  =
    Dispatcher.sequential[IO] evalTap { dispatcher =>
      IO(canvas.onmousemove = { (event: MouseEvent) =>
        // gets position of mouse click, subtract that by the canvas dimensions and we get the mouse positions relative to the canvas.
        val rect = canvas.getBoundingClientRect()
        val mouseX = event.clientX - rect.left
        val mouseY = event.clientY - rect.top

        // we need to scale the mouseX and mouseY for how the browser scales the canvas
        val xscale = canvas.width.toDouble / rect.width
        val yscale = canvas.height.toDouble / rect.height
        val scaledX = mouseX * xscale
        val scaledY = mouseY * yscale

        def checkHover: IO[Unit] = {
          for {
            _ <-  rectRef.get.flatTap { rects =>
                    rects.traverse {
                      case Rectangle(event, x, y, width, height, color) => {
                        if (scaledX >= x && scaledX <= x + width && scaledY <= y && scaledY >= y + height) {
                            val ev = event(0) // event is a (RequestEvent, Int)

                            // make an EventDetails object
                            val info: EventDetails = ev match {
                              case RequestEvent.Server(time) =>
                                EventDetails("Start", time, "", "", "")
                              case RequestEvent.Request(start, url) =>
                                val shortUrl = url.split("\\?")(0)
                                EventDetails("Request", start, "ongoing", "ongoing", s"$shortUrl")
                              case RequestEvent.Success(start, url, end, duration) =>
                                val shortUrl = url.split("\\?")(0)
                                EventDetails("Success", start, end, s"$duration ms", s"$shortUrl")
                              case RequestEvent.Failure(start, url, end, msg) => 
                                val duration = Duration.between(LocalDateTime.parse(start), LocalDateTime.parse(end)).toMillis()
                                EventDetails(s"Failure: $msg", start, end, s"$duration ms", url)
                              case RequestEvent.Partial(end, msg) =>
                                if (msg.contains("msg: ")) {
                                  val error = msg.split("msg: ")(1)
                                  EventDetails(s"Failure: $error", "unknown", end, "failed", "unknown")
                                } else {
                                  val duration = msg.split("duration: ")(1)
                                  EventDetails("Success", "unknown", end, duration, "unknown")
                                }
                            }
                            
                            // updating the ref
                            eventRef.set(Some(info))
                        } else {
                          IO.unit
                        }
                      }
                    }
                  }
          } yield ()
        }
        dispatcher.unsafeRunAndForget(checkHover)
      })
    }

  /**
   * Updates and returns the new scroll position after zooming
   * 
   * @param canvas
   * @param currScroll scroll top position before zoom
   * @param halfViewport height of viewport/canvas that user sees divided in half
   * @param zoomRatio new zoom level / previous zoom level 
   * @return new scroll top position
  */
  private def updateScrollTop(
    canvas: HTMLCanvasElement,
    currScroll: Double,
    halfViewport: Double,
    zoomRatio: Double
    ): IO[Double] = {

      //absolute "center" of the viewport. scroll position is relative to the entire height(scrollable area)
      val center = currScroll + halfViewport

      //multiplying center by the zoomRatio gives us the new center that we want to be the "center" of the viewport
      //the same timestep should also be at this position(that's why we want this)
      val newCenter = center * zoomRatio

      //because the viewport height that the user sees remains the same even if zoom level changes, then we can now 
      //obtain a scroll position such that the new center position is seen as the center of the viewport. 
      val newScroll = newCenter - halfViewport
      IO(canvas.parentElement.scrollTop = newScroll).as(newScroll) 
    }
}

