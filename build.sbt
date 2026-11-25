ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "org.llm4s"
ThisBuild / organizationName := "llm4s"

val llm4sVersion = "0.7.0"

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
    
    // Compiler options for Scala 3
    scalacOptions ++= Seq(
      "-feature",
      "-unchecked",
      "-deprecation"
    ),
    
    // Dependencies
    libraryDependencies ++= Seq(
      // LLM4S modules: agent runtime, LLM providers, image generation, speech and tracing
      "org.llm4s" %% "llm4s-agent"     % llm4sVersion,
      "org.llm4s" %% "llm4s-openai"    % llm4sVersion,
      "org.llm4s" %% "llm4s-anthropic" % llm4sVersion,
      "org.llm4s" %% "llm4s-image"     % llm4sVersion,
      // Langfuse tracing backend (TRACING_MODE=langfuse)
      "org.llm4s" %% "llm4s-observability" % llm4sVersion,
      // Cloud text-to-speech and speech-to-text; the local Vosk engine (and its native libraries) is not used
      ("org.llm4s" %% "llm4s-speech" % llm4sVersion)
        .exclude("com.alphacephei", "vosk")
        .exclude("net.java.dev.jna", "jna"),
      
      // Cask for web server
      "com.lihaoyi" %% "cask" % "0.11.3",
      
      // Core dependencies
      "com.lihaoyi"   %% "upickle"         % "4.4.3",
      "ch.qos.logback" % "logback-classic" % "1.5.34",
      "io.github.cdimascio"           %  "dotenv-java"     % "3.2.0",
      
      // HTTP and WebSocket
      "com.lihaoyi"       %% "requests"        % "0.9.0",
      "org.java-websocket" % "Java-WebSocket"  % "1.6.0",
      
      
      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.llm4s"     %% "llm4s-testkit" % llm4sVersion % Test
    )
  )

// Convenient command aliases
addCommandAlias("szorkStart", "reStart")
addCommandAlias("szorkStop", "reStop")
addCommandAlias("szorkRestart", "reRestart")
addCommandAlias("szorkStatus", "reStatus")
// Note: For triggered restart mode, use: sbt "~szorkStart"
