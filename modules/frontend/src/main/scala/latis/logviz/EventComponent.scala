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
import fs2.dom.HtmlElement
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.model.Rectangle
import latis.logviz.model.RequestEvent



/**
  * Draws canvas components with log event details
  * 
  * @param stream stream of log events from EventClient
  * @param requestDetails div for hover feature
  */
class EventComponent(stream: Stream[IO, Event], requestDetails: HtmlElement[IO]) {
  val timestampFormatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00")
  val pixelsPerSec = 1.0

 
  def render: Resource[IO, HtmlElement[IO]] = 
    
    for {
      canvasIO  <- canvasTag(idAttr:= "canvas")
      sizer     <- div(idAttr:= "sizer")
      timeline  <- div(idAttr:= "timeline", sizer, canvasIO)
      canvas    =  canvasIO.asInstanceOf[HTMLCanvasElement]
      context   =  canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      eParser   <- Resource.eval(EventParser())
      _         <- Resource.eval(stream.evalTap(event =>
                    eParser.parse(event)).compile.drain)
      scrollRef <- Resource.eval(Ref[IO].of(0.0))
      isLive    <- Resource.eval(Ref[IO].of(true))
      rectRef   <- Resource.eval(Ref[IO].of(List[Rectangle]()))
      _         <- animate(canvas, context, sizer.asInstanceOf[HTMLElement], eParser, scrollRef, isLive, rectRef)
      _         <- hover(canvas, requestDetails.asInstanceOf[HTMLElement], rectRef)
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

    val topTS = current.minusSeconds((top / pixelsPerSec).toLong)
    val currTruncated = topTS.truncatedTo(ChronoUnit.MINUTES)
    val mins = ((math.min(convertTime(endTime, topTS), canvas.height.toDouble) /pixelsPerSec / 60)).toInt
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
  */
  private def animate(
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    sizer: HTMLElement,
    parser: EventParser,
    prevScrollPos: Ref[IO, Double],
    isLive: Ref[IO, Boolean],
    rectRef: Ref[IO, List[Rectangle]]
  ): Resource[IO, Dispatcher[IO]] =
    Dispatcher.sequential[IO] evalTap{ dispatcher => 

      def go(timestamp: Double): IO[Unit] = 
        for {
          top     <- IO(canvas.parentElement.scrollTop)
          live    <- isLive.get
          prevTop <- prevScrollPos.get
          _       <- if (live || prevTop != top) {
                      for {
                        tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                        height  =  tlRect.height
                        _       <- IO(canvas.width = tlRect.width.toInt)
                        _       <- IO(canvas.height = tlRect.height.toInt)
                        width   <- IO(canvas.width - 150)     // offset by 150 for total width of canvas that rectangles should cover
                        now     <- IO(LocalDateTime.now(ZoneOffset.UTC))
                        end     <- IO(now.toLocalDate.atStartOfDay())
                        _       <- IO(sizer.style.height = s"${convertTime(end, now)+2}px")
                        maxCol  <- parser.getMaxConcurrent()
                        // _       <-  makeRect(now, em.events, em.compEvents, em.rectangles, em.maxCounter, height, top, width)
                        events  <- parser.getEvents()
                        rects   = Rectangles.makeRectangles(now, height, top, width/maxCol, events)
                        _       <- rectRef.update(_ => rects)
                        _       <- drawCanvas(now, end, canvas, context, rects, maxCol, top, width)
                        _       <- prevScrollPos.update(_ => top)
                        _       <- if (top == 0.0) {
                                    isLive.update(_ => true)
                                  } else {
                                    isLive.update(_ => false)
                                  }
                      } yield ()
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
    requestDetails: HTMLElement,
    rectRef: Ref[IO, List[Rectangle]]
    ): Resource[IO, Dispatcher[IO]]  =
    Dispatcher.sequential[IO] evalTap { dispatcher =>
      IO(canvas.onmousemove = { (event: MouseEvent) =>
        // gets position of mouse click, subtract that by the canvas dimensions and we get the mouse positions relative to the canvas.
        val rect = canvas.getBoundingClientRect()
        val mouseX = event.clientX - rect.left
        val mouseY = event.clientY - rect.top

        def checkHover: IO[Unit] = {
          for {
            _ <-  rectRef.get.flatTap { rects =>
                    rects.traverse {
                      case Rectangle(event, x, y, width, height, color) => {
                        if (mouseX >= x && mouseX <= x + width && mouseY <= y && mouseY >= y + height) {
                          IO{
                            val ev = event(0) // event is a (RequestEvent, Int)
                            // we currently don't get the correlation id or thread id

                            // get path, start time, end time, elapsed time, thread id, and if there was an exception (None)
                            val info: String = ev match {
                              case RequestEvent.Server(time) =>
                                s"Event: Start\ntime: $time"
                              case RequestEvent.Request(start, url) =>
                                val shortUrl = url.split(" Headers")(0)
                                val duration = Duration.between(LocalDateTime.parse(start), LocalDateTime.now(ZoneOffset.UTC))
                                s"Event: Request\r\nPath: $shortUrl\r\nStart Time: $start\r\nEnd Time: Ongoing\r\nElapsed: ${duration.toMinutesPart()} minutes, ${duration.toSecondsPart()} seconds\r\nException: (None)"
                              case RequestEvent.Success(start, url, end, duration) =>
                                val shortUrl = url.split(" Headers")(0)
                                s"Event: Success\r\nPath: $shortUrl\r\nStart Time: $start\r\nEnd Time: $end\r\nElapsed: $duration ms\r\nException: (None)"
                              case RequestEvent.Failure(start, url, end, msg) => s"$event"
                            }
                            requestDetails.style.fontSize = "20px"
                            requestDetails.style.whiteSpace = "pre"
                            requestDetails.textContent = info
                          }
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

