ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .settings(
    name := "szork",
    // https://mvnrepository.com/artifact/org.llm4s/llm4s_2.13/v0.1.6
    libraryDependencies += "org.llm4s" %% "llm4s" % "0.1.9"
  )
