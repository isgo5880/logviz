package latis.logviz

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Ref
import calico.html.io.{*, given}
import fs2.dom.HtmlElement

/**
  * Component for adjusting zoom levels
  * 
  * Changing zoom levels impacts the pixels per second value for 
  * drawing the canvas and rectangles
  * Current Max: 10 pixels per second
  * Current Min: 0.2 pixels per second
  *
  * @param zoomLevel ref for holding pixels per second/zoom level value to be used in other components
  */
class ZoomComponent(zoomLevel: Ref[IO, Double]) {
  def render: Resource[IO, HtmlElement[IO]] = 
    div(
      idAttr:= "zoom", 
      div(
        button(
          cls:= "zoom-button",
          typ := "button",
          "-",
          onClick {
            zoomLevel.update(z => math.max(0.2, z - 0.1))
          }
        ),
        span(
          "ZOOM"
        ),
        button(
          cls:= "zoom-button",
          typ := "button",
          "+",
          onClick {
            zoomLevel.update(z => math.min(10.0, z + 0.1))
          }
        )
      ),
      div(
        button(
          typ := "button",
          "0.2x",
          onClick {
            zoomLevel.set(0.2)
          }
        ),
        button(
          typ := "button",
          "0.5x",
          onClick {
            zoomLevel.set(0.5)
          }
        ),
        button(
          typ := "button",
          "0.75x",
          onClick {
            zoomLevel.set(0.75)
          }
        ),
        button(
          typ := "button",
          "1.0x",
          onClick {
            zoomLevel.set(1.0)
          }
        ),
        button(
          typ := "button",
          "2.0x",
          onClick {
            zoomLevel.set(2.0)
          }
        ),
        button(
          typ := "button",
          "5.0x",
          onClick {
            zoomLevel.set(5.0)
          }
        ),
        button(
          typ := "button",
          "10.0x",
          onClick {
            zoomLevel.set(10.0)
          }
        )      
      )
    ) 
}