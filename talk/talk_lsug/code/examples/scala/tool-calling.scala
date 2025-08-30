// Tool Calling System Example
import org.llm4s.toolapi._

// Define a custom tool
object WeatherTool extends Tool {
  val name = "get_weather"
  val description = "Get current weather for a location"
  
  def execute(args: Map[String, Any]): Either[Error, String] = {
    val location = args("location").toString
    // Call weather API
    Right(s"Weather in $location: 22°C, Sunny")
  }
}

// Register and use tools
val toolRegistry = new ToolRegistry(Seq(
  WeatherTool,
  ImageGenerationTool,
  DatabaseQueryTool
))

val agent = new Agent(client)
  .withTools(toolRegistry)
  .withSystemPrompt("You are a helpful assistant")

// Agent automatically calls tools as needed
val response = agent.chat("What's the weather in London?")
// Agent calls WeatherTool automatically