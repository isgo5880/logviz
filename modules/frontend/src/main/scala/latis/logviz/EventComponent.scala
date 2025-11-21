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
  liveRef: SignallingRef[IO, Boolean]
  ) {
  val timestampFormatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00")
  val pixelsPerSec = 1.0

 
  /**
    * returns scrollable canvas component
    */
  def render: Resource[IO, HtmlElement[IO]] = 
    
    for {
      sup         <- Supervisor[IO](await=true)
      canvasIO    <- canvasTag(idAttr:= "canvas")
      sizer       <- div(idAttr:= "sizer")
      timeline    <- div(idAttr:= "timeline", sizer, canvasIO)
      canvas      =  canvasIO.asInstanceOf[HTMLCanvasElement]
      context     =  canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      eParser     <- Resource.eval(EventParser())
      _           <- Resource.eval(sup.supervise(signal.discrete.switchMap { stream => 
                      Stream.eval(stream.evalTap{ event =>
                        eParser.parse(event)
                      }.compile.drain)
                    }.compile.drain).void)
      scrollRef   <- Resource.eval(Ref[IO].of(0.0))
      isLive      <- Resource.eval(Ref[IO].of(true))
      rectRef     <- Resource.eval(Ref[IO].of(List[Rectangle]()))
      end         <- Resource.eval(endTime.get)
      prevEndRef  <- Resource.eval(Ref[IO].of(end))
      _           <- animate(canvas, 
                      context, 
                      sizer.asInstanceOf[HTMLElement],
                      eParser,
                      scrollRef, 
                      isLive, 
                      rectRef,
                      prevEndRef)
      _           <- hover(canvas, eventRef, rectRef)
    } yield(timeline)
  
  /**
   * Conversion to get position of a timestamp on the canvas
   * 
   * Find the difference in seconds between the timestamp we're interested in and the current time
   * Multiply by abitrary pixels per second to get y position on the canvas to start drawing on. 
   * Same as saying how far off we are from the top of canvas(current time)
   * [[https://www.geeksforgeeks.org/java/java-time-duration-class-in-java/#]]
  */
  private def convertTime(timestamp: LocalDateTime, current: LocalDateTime): Double = 
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
    current: LocalDateTime,
    endTime: LocalDateTime,
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    rects: List[Rectangle],
    cols: Int,
    top: Double,
    width: Int
  ): IO[Unit] =
    //TODO: used to interpreting endtime as the "start time" of the time range. change name to match newer changes

    //computes what the top of the canvas(viewport) is
    //takes the end date range and subtracts from the scroll position to 
    //know what the timestamp is at the top of the viewport
    val topTS = current.minusSeconds((top / pixelsPerSec).toLong)
    val currTruncated = topTS.truncatedTo(ChronoUnit.MINUTES)
    
    //calculates the number of minutes/timestamps to be drawn
    val mins = ((math.min(convertTime(endTime, topTS), canvas.height.toDouble) 
                /pixelsPerSec / 60)).toInt

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
              val y = convertTime(timestamp, topTS).toDouble
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
   * @param prevEndRef ref to hold previous end time. used to determine if need to redraw
  */
  private def animate(
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    sizer: HTMLElement,
    parser: EventParser,
    prevScrollPos: Ref[IO, Double],
    isLive: Ref[IO, Boolean],
    rectRef: Ref[IO, List[Rectangle]],
    prevEndRef: Ref[IO, LocalDateTime]
  ): Resource[IO, Dispatcher[IO]] =
    Dispatcher.sequential[IO] evalTap{ dispatcher => 

      def go(timestamp: Double): IO[Unit] = 
        for {
          top     <- IO(canvas.parentElement.scrollTop)
          live    <- isLive.get
          prevTop <- prevScrollPos.get
          start   <- startTime.get
          prevEnd <- prevEndRef.get
          end     <- endTime.get
          liveTog <- liveRef.get

          //*** liveTog will currently always be true 

          //reason why live button is important here is 
          //because we don't want resume live at top of canvas functionality when we set end date range in the past
          //so live button tells us to allow live updating when at the top hence need for both liveTog and live

          //if liveTog(want live updates when at the top) and if live(meaning we're at top of the canvas) then we always redraw each animation frame
          _       <- if (liveTog && (live || prevTop != top)) {

                      for {
                        //if user changes size of browser or anything, then just adjusting canvas to fit those changes
                        tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                        height  =  tlRect.height
                        _       <- IO(canvas.width = tlRect.width.toInt)
                        _       <- IO(canvas.height = tlRect.height.toInt)

                        //width to be used for drawing area
                        //subtracting by 150 to make room for timestamps to be drawn. So the total width available to event/column drawing is 150 pixels less
                        width   <- IO(canvas.width - 150) 

                        //grabbing the current time to be used as endtime/top of canvas
                        endTime <- IO(LocalDateTime.now(ZoneOffset.UTC))
                        //updating sizer height used for how much you can scroll
                        _       <- IO(sizer.style.height = s"${convertTime(start, endTime)+2}px")

                        maxCol  <- parser.getMaxConcurrent()
                        events  <- parser.getEvents()
                        rects   = Rectangles.makeRectangles(endTime,
                                   height,
                                   top,
                                   width/maxCol,
                                   events,
                                   start)
                        _       <- rectRef.update(_ => rects)
                        _       <- drawCanvas(endTime,
                                    start,
                                    canvas,
                                    context,
                                    rects,
                                    maxCol,
                                    top,
                                    width)
                        _       <- prevScrollPos.update(_ => top)
                        _       <- if (top == 0.0) {
                                    isLive.update(_ => true)
                                  } else {
                                    isLive.update(_ => false)
                                  }
                      } yield ()

                      //*** unused until timecomponent is re-introduced
                      //only redraw when scrolling or there are changes if live is not toggled
                    } else if (!liveTog && (prevEnd != end || prevTop != top)){
                        for {
                          tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                          height  =  tlRect.height
                          _       <- IO(canvas.width = tlRect.width.toInt)
                          _       <- IO(canvas.height = tlRect.height.toInt)
                          width   <- IO(canvas.width - 150)
                          _       <- IO(sizer.style.height = s"${convertTime(start, end)+2}px")
                          maxCol  <- parser.getMaxConcurrent()
                          events  <- parser.getEvents()
                          rects   = Rectangles.makeRectangles(end,
                                    height,
                                    top,
                                    width/maxCol,
                                    events,
                                    start)
                          _       <- rectRef.update(_ => rects)
                          _       <- drawCanvas(end,
                                      start,
                                      canvas,
                                      context,
                                      rects,
                                      maxCol,
                                      top,
                                      width)
                          _       <- prevScrollPos.update(_ => top)
                          _       <- prevEndRef.set(end)
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
                                val duration = Duration.between(LocalDateTime.parse(start), LocalDateTime.parse(end))
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
}

