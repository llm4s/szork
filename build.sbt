ThisBuild / scalaVersion := "3.8.4"

// llm4s modules are published together, at one version
val llm4sVersion = "0.5.1"
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
    
    // Compiler options for Scala 3
    scalacOptions ++= Seq(
      "-feature",
      "-unchecked",
      "-deprecation",
      "-source:3.3",
      // Suppress exhaustiveness warnings that are false positives with cask annotations
      "-Wconf:msg=match may not be exhaustive:s"
    ),
    
    // Dependencies
    libraryDependencies ++= Seq(
      // LLM4S: the core API plus the modules szork uses
      "org.llm4s" %% "llm4s-core"      % llm4sVersion, // LLMClient, messages, tool API, config
      "org.llm4s" %% "llm4s-agent"     % llm4sVersion, // Agent: the Dungeon Master's tool-calling loop
      "org.llm4s" %% "llm4s-image"     % llm4sVersion, // DALL-E, HuggingFace and Stable Diffusion image clients
      "org.llm4s" %% "llm4s-openai"    % llm4sVersion, // provider = "openai"
      "org.llm4s" %% "llm4s-anthropic" % llm4sVersion, // provider = "anthropic"
      "org.llm4s" %% "llm4s-ollama"    % llm4sVersion, // provider = "ollama" (local models)
      
      // Cask for web server
      "com.lihaoyi" %% "cask" % "0.11.3",
      
      // Core dependencies
      "org.typelevel" %% "cats-core"       % "2.13.0",
      "com.lihaoyi"   %% "upickle"         % "4.4.3",
      "ch.qos.logback" % "logback-classic" % "1.5.34",
      "io.github.cdimascio"           %  "dotenv-java"     % "3.2.0",
      "org.slf4j"      % "log4j-over-slf4j" % "2.0.18",
      
      // HTTP and WebSocket
      "com.lihaoyi"       %% "requests"        % "0.9.0",
      "org.java-websocket" % "Java-WebSocket"  % "1.6.0",
      "com.lihaoyi"                   %% "ujson" % "4.4.3",
      
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
