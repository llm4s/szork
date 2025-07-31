ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "szork",
    // https://mvnrepository.com/artifact/org.llm4s/llm4s
    libraryDependencies += "org.llm4s" % "llm4s_2.13" % "0.1.6"
  )
