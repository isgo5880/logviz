ThisBuild / organization := "io.latis-data"
ThisBuild / scalaVersion := "3.3.6"

val catsVersion = "2.13.0"
val catsEffectVersion = "3.7.0"
val circeVersion = "0.14.15"
val fs2Version = "3.13.0"
val http4sVersion = "0.23.34"
val log4catsVersion = "2.8.0"
val logbackVersion = "1.5.33"
val pureconfigVersion = "0.17.10"

val commonSettings = Seq(
  scalacOptions -= "-Xfatal-warnings",
  scalacOptions += {
    if (insideCI.value) "-Wconf:any:e" else "-Wconf:any:w"
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(
    app,
    backend,
    frontend,
    shared.js,
    shared.jvm,
    splunk
  )

lazy val app = project
  .in(file("modules/app"))
  .dependsOn(backend)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime
    ),
    run / fork := true
  )

lazy val backend = project
  .in(file("modules/backend"))
  .dependsOn(shared.jvm)
  .dependsOn(splunk)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.gnieh" %% "fs2-data-json" % "1.14.0",
      "org.gnieh" %% "fs2-data-json-circe" % "1.14.0",
      "org.gnieh" %% "fs2-data-text" % "1.14.0",
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
    )
  )

lazy val frontend = project
  .in(file("modules/frontend"))
  .dependsOn(shared.js)
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "calico" % "0.2.3",
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "io.circe" %%% "circe-parser" % circeVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "com.armanbilge" %%% "fs2-dom" % "0.2.1",
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "org.http4s" %%% "http4s-client" % http4sVersion,
      "org.http4s" %%% "http4s-dom" % "0.2.12"
    )
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/shared"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.http4s" %%% "http4s-circe" % http4sVersion
    )
  )

lazy val splunk = project
  .in(file("modules/splunk"))
  .dependsOn(shared.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "io.circe" %%% "circe-core" % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-parser" % circeVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "org.http4s" %%% "http4s-dsl" % http4sVersion,
      "org.http4s" %%% "http4s-ember-client" % http4sVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "com.github.pureconfig" %%% "pureconfig-core" % pureconfigVersion,
      "com.github.pureconfig" %%% "pureconfig-cats-effect" % pureconfigVersion,
      "com.github.pureconfig" %%% "pureconfig-http4s" % pureconfigVersion
    )
  )

lazy val devCompile = taskKey[Unit](
  "Compile the frontend for dev and copy to the backend resource directory"
)

devCompile := {
  val js = (frontend / Compile / fastLinkJSOutput).value
  val dst = (backend / Compile / resourceDirectory).value
  IO.copyFile(js / "main.js", dst / "main.js")
}

lazy val prodCompile = taskKey[Unit](
  "Compile the frontend for prod and copy to the backend resource directory"
)

prodCompile := {
  val js = (frontend / Compile / fullLinkJSOutput).value
  val dst = (backend / Compile / resourceDirectory).value
  IO.copyFile(js / "main.js", dst / "main.js")
}

addCommandAlias("runDev", "; devCompile; app/reStart")
addCommandAlias("runProd", "; prodCompile; app/reStart")
