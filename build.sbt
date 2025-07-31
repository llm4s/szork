ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "szork",
    libraryDependencies ++= Seq(
      "com.softwaremill.llm4s" %% "llm4s" % "0.1.0"
    )
  )

