ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "szork",
    libraryDependencies ++= Seq(
      "ai.llm4s" %% "llm4s-core" % "0.1.3"
    )
  )
