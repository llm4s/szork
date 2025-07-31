ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "szork",
    libraryDependencies ++= Seq(
      "org.llm4s" % "llm4s_2.13" % "0.1.6"
    )
  )
