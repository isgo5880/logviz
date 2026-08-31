package latis.logviz

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.LocalDateTime
import java.time.ZoneOffset

import org.scalajs.dom.HTMLInputElement
import cats.syntax.all.*
import cats.effect.IO
import cats.effect.Resource
import calico.html.io.{*, given}
import fs2.concurrent.SignallingRef
import fs2.dom.HtmlElement

class TimeRangeComponent(
  startRef: SignallingRef[IO, LocalDateTime],
  endRef: SignallingRef[IO, LocalDateTime]
) {
  val formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  def render: Resource[IO, HtmlElement[IO]] =
    for {
      start <- Resource.eval(startRef.get)
      
      startInput  <- div(
                      idAttr := "start-time",
                      label("Start: "),
                      input.withSelf{ self => 
                        (
                          `type` := "datetime-local",
                          value  := s"${start.format(formatter)}",
                          maxAttr <-- endRef.map(time => time.format(formatter)),
                          minAttr := "1970-01-01 00:00",
                          onChange --> {
                            _.evalMap(_ => self.value.get).filter(_.nonEmpty).foreach {v =>
                              val inputElem = self.asInstanceOf[HTMLInputElement]
                              if (inputElem.checkValidity()) {
                                Either.catchOnly[DateTimeParseException](LocalDateTime.parse(v)) match
                                  case Left(value) => throw new IllegalArgumentException(
                                    "Invalid time") 
                                  case Right(value) => startRef.set(value)
                              } else {
                                IO.unit
                              }
                              }
                          }
                        )
                       
                      },
                      //validity icon from https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/input/datetime-local
                      span(cls := "validity"),
                    ) 
      endInput    <- div(
                      idAttr := "end-time",
                      label("End: "),
                      input.withSelf{ self => 
                        (
                          idAttr := "end-time",
                          `type` := "datetime-local",
                          value <-- endRef.map(time => time.format(formatter)),
                          minAttr <-- startRef.map(time => time.format(formatter)),
                          onClick(_ => {
                            val inputElem = self.asInstanceOf[HTMLInputElement]
                            IO(inputElem.max = LocalDateTime.now(ZoneOffset.UTC).format(formatter))
                          }),
                          onChange --> {
                            _.evalMap(_ => self.value.get).filter(_.nonEmpty).foreach { v => 
                              val inputElem = self.asInstanceOf[HTMLInputElement]
                              if (inputElem.checkValidity()) {
                                Either.catchOnly[DateTimeParseException](LocalDateTime.parse(v)) match
                                  case Left(value) => throw new IllegalArgumentException(
                                    "Invalid time") 
                                  case Right(value) => endRef.set(value)
                              } else {
                                IO.unit
                              }
                            }
                          }
                        )
                      },
                      span(cls := "validity")
                    )
      test  <- div(idAttr:= "time-range", div(idAttr:= "time-range-selection", startInput, endInput))

    } yield(test)
  
}

