
lazy val akkaHttpVersion = "10.2.3"
lazy val akkaVersion     = "2.6.12"
lazy val playJsonVersion = "2.9.2"
lazy val akkaHttpPlayJsonVersion = "1.35.3"
lazy val catsCoreVersion   = "2.1.1"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization      := "com.tarai",
      scalaVersion      := "2.13.4",
      scalafmtOnCompile := true
    )),
    name := "service-registry",
    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-http"                % akkaHttpVersion,
        "com.typesafe.play" %% "play-json"                % playJsonVersion,
        "de.heikoseeberger" %% "akka-http-play-json"      % akkaHttpPlayJsonVersion,
        "com.typesafe.akka" %% "akka-actor-typed"         % akkaVersion,
        "com.typesafe.akka" %% "akka-stream"              % akkaVersion,
        "ch.qos.logback"    % "logback-classic"           % "1.2.3",
        "ch.qos.logback"    % "logback-core"              % "1.2.3",
        "org.typelevel"     %% "cats-core"                % catsCoreVersion,

        "com.typesafe.akka" %% "akka-http-testkit"        % akkaHttpVersion % Test,
        "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion     % Test,
        "org.scalatest"     %% "scalatest"                % "3.1.4"         % Test
      )
  )

enablePlugins(UniversalPlugin)
enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)
