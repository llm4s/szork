ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "org.llm4s"
ThisBuild / organizationName := "llm4s"

lazy val root = (project in file("."))
  .enablePlugins(RevolverPlugin)
  .settings(
    name := "szork",
    fork := true,
    
    // Revolver settings for hot reloading
    reStart / mainClass := Some("org.llm4s.szork.api.SzorkServer"),
    reStart / javaOptions ++= Seq(
      "-Xmx1g",
      "-XX:MaxMetaspaceSize=512m"
    ),
    
    // Watch for changes in source directories
    watchSources ++= Seq(
      (ThisBuild / baseDirectory).value / "src"
    ).flatMap(dir => (dir ** "*.scala").get),
    
    // Revolver options
    reStartArgs := Seq(),
    Global / cancelable := true,
    
    // Compiler options for Scala 2.13
    scalacOptions ++= Seq(
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Wunused:nowarn",
      "-Wunused:imports",
      "-Wunused:privates",
      "-Wunused:locals",
      "-Wunused:patvars",
      "-Wunused:params",
      "-Wunused:linted",
      // Suppress exhaustiveness warnings that are false positives with cask annotations
      "-Wconf:msg=match may not be exhaustive:s"
    ),
    
    // Dependencies
    libraryDependencies ++= Seq(
      // Core LLM4S library
      "org.llm4s" %% "core" % "0.1.14",
      
      // Cask for web server
      "com.lihaoyi" %% "cask" % "0.10.2",
      
      // Core dependencies
      "org.typelevel" %% "cats-core"       % "2.13.0",
      "com.lihaoyi"   %% "upickle"         % "4.2.1",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "io.github.cdimascio"           %  "dotenv-java"     % "3.0.0",
      "org.slf4j"      % "log4j-over-slf4j" % "2.0.16",
      
      // LLM provider dependencies
      "com.azure"          % "azure-ai-openai" % "1.0.0-beta.16",
      "com.anthropic"      % "anthropic-java"  % "2.2.0",
      "com.knuddels"       % "jtokkit"         % "1.1.0",
      
      // HTTP and WebSocket
      "com.lihaoyi"       %% "requests"        % "0.9.0",
      "org.java-websocket" % "Java-WebSocket"  % "1.6.0",
      "com.lihaoyi"                   %% "ujson" % "4.2.1",
      
      // Document processing (none currently needed)
      // Environment configuration (already added above)
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )

// Convenient command aliases
addCommandAlias("szorkStart", "reStart")
addCommandAlias("szorkStop", "reStop")
addCommandAlias("szorkRestart", "reRestart")
addCommandAlias("szorkStatus", "reStatus")
// Note: For triggered restart mode, use: sbt "~szorkStart"
