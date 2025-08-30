// Add LLM4S to your Scala project

// For Scala 3
libraryDependencies ++= Seq(
  "com.llm4s" %% "llm4s-core" % "0.5.0",
  "com.llm4s" %% "llm4s-agents" % "0.5.0",
  "com.llm4s" %% "llm4s-tools" % "0.5.0"
)

// Cross-compilation for Scala 2.13 and 3
crossScalaVersions := Seq("2.13.12", "3.3.1")

// Optional: Add specific providers
libraryDependencies ++= Seq(
  "com.llm4s" %% "llm4s-openai" % "0.5.0",
  "com.llm4s" %% "llm4s-anthropic" % "0.5.0"
)

// Optional: Observability
libraryDependencies += "com.llm4s" %% "llm4s-langfuse" % "0.5.0"