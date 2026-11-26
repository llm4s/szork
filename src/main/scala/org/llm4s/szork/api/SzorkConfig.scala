package org.llm4s.szork.api

import org.llm4s.szork.error.*
import org.llm4s.szork.error.ErrorHandling.*
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.szork.config.{ConfigReader, EnvLoader}
import org.llm4s.szork.game.GameEngine
import org.llm4s.szork.media.{MediaCache, SceneImages}
import org.llm4s.szork.persistence.StepPersistence
import org.llm4s.llmconnect.model.ReasoningEffort
import org.llm4s.security.Secret
import org.slf4j.Logger
import java.nio.file.Paths

/** Image generation provider options
  */
sealed trait ImageProvider
object ImageProvider {
  case object None extends ImageProvider
  case object HuggingFace extends ImageProvider
  case object HuggingFaceSDXL extends ImageProvider
  case object OpenAIDalle2 extends ImageProvider
  case object OpenAIDalle3 extends ImageProvider
  case object OpenAIGptImage extends ImageProvider
  case object LocalStableDiffusion extends ImageProvider

  def fromString(s: String): ImageProvider = s.toLowerCase match {
    case "none" | "" => None
    case "huggingface" | "hf" => HuggingFace
    case "huggingface-sdxl" | "hf-sdxl" | "sdxl" => HuggingFaceSDXL
    case "dalle2" | "dall-e-2" | "openai-dalle2" => OpenAIDalle2
    case "dalle3" | "dall-e-3" | "openai-dalle3" => OpenAIDalle3
    case "openai" | "gpt-image" | "gptimage" | "openai-gpt-image" => OpenAIGptImage
    case "local" | "localsd" | "local-sd" | "stable-diffusion" | "sd" => LocalStableDiffusion
    case _ => None
  }

  def toString(provider: ImageProvider): String = provider match {
    case None => "None"
    case HuggingFace => "HuggingFace"
    case HuggingFaceSDXL => "HuggingFace SDXL"
    case OpenAIDalle2 => "OpenAI DALL-E 2"
    case OpenAIDalle3 => "OpenAI DALL-E 3"
    case OpenAIGptImage => "OpenAI GPT Image"
    case LocalStableDiffusion => "Local Stable Diffusion"
  }
}

/** Configuration for the Szork game server.
  *
  * [[SzorkConfig.load]] is the one reader of Szork's own `SZORK_*` settings; everything else is handed the values it
  * needs. Provider credentials (OPENAI_API_KEY and the like) are read where the clients that use them are built.
  */
case class SzorkConfig(
  // Server configuration
  port: Int = 8090,
  wsPort: Int = 9002,
  host: String = "127.0.0.1",

  // Image generation configuration
  imageGenerationEnabled: Boolean = true,
  imageProvider: ImageProvider = ImageProvider.None,
  // The provider's model, when not its default (SZORK_IMAGE_MODEL)
  imageModel: Option[String] = None,
  // How well GPT Image models draw (SZORK_IMAGE_QUALITY: low, medium, high or auto), and the longer side in pixels
  // pictures are kept and sent at (SZORK_IMAGE_PIXELS)
  imageQuality: String = SceneImages.DefaultQuality,
  imagePixels: Int = SceneImages.DefaultPictureSide,

  // Audio/speech/media feature flags and settings
  ttsEnabled: Boolean = true,
  sttEnabled: Boolean = true,
  musicEnabled: Boolean = true,
  speech: SpeechConfig = SpeechConfig(),
  mediaNetwork: MediaNetworkConfig = MediaNetworkConfig(),
  musicPoll: MusicPollConfig = MusicPollConfig(),

  // LLM configuration
  llmConfig: Option[LLMConfig] = None,
  // How much of the game's conversation each Dungeon Master model call is sent (None: all of it)
  dmContextWindow: Option[ContextWindowConfig] = Some(GameEngine.DefaultContextWindow),
  // Whether player commands pass the Dungeon Master's prompt-injection guardrail (PlayerInputGuardrail)
  inputGuardrailEnabled: Boolean = true,
  // How hard the designer, the Dungeon Master and its subagents reason, on models that do (None: the model's default)
  reasoningEffort: Option[ReasoningEffort] = Some(SzorkConfig.DefaultReasoningEffort),
  // The Dungeon Master's subagents: NPC dialogue and hints, and the model they use
  subagents: SubagentConfig = SubagentConfig(),

  // Saves and the media cache
  autoSaveEnabled: Boolean = true,
  savesDirectory: String = "szork-saves",
  // The adventure library: ready-made adventures, their pictures and the mood music
  adventuresDirectory: String = SzorkConfig.DefaultAdventuresDirectory,
  cache: MediaCacheConfig = MediaCacheConfig(),

  // How long a session with no connection lives before it expires
  sessionTtlMinutes: Int = SzorkConfig.DefaultSessionTtlMinutes,

  // Browser origins allowed to open the WebSocket and to call the HTTP API
  allowedOrigins: Set[String] = SzorkConfig.defaultOrigins(8090),
  // Names (or name:port) the HTTP API answers to besides this machine's loopback names (SZORK_ALLOWED_HOSTS)
  allowedHosts: Set[String] = Set.empty,

  // Settings that were given but cannot be used; `validate` reports them
  problems: List[String] = Nil
) {
  def sessionTtlMillis: Long = sessionTtlMinutes.toLong * 60 * 1000

  /** The Host headers the HTTP API answers to, as `name:port` (or a bare name, for any port); None when any is allowed.
    *
    * A server on a loopback address answers only to the loopback names, so a web page whose name a DNS rebinding points
    * at 127.0.0.1 cannot use the API. A server listening on every interface (SZORK_HOST=0.0.0.0) cannot know the names
    * it is reached by, so it checks the Host only when SZORK_ALLOWED_HOSTS lists them.
    */
  def httpHosts: Option[Set[String]] =
    if (SzorkConfig.isWildcard(host) && allowedHosts.isEmpty) None
    else {
      val own = if (SzorkConfig.isWildcard(host)) Set.empty[String] else Set(host)
      Some(
        (SzorkConfig.LoopbackNames ++ own).map(name => s"${SzorkConfig.bracketed(name)}:$port".toLowerCase) ++
          allowedHosts.map(_.toLowerCase))
    }
}

case class LLMConfig(
  provider: String,
  apiKey: String,
  model: Option[String] = None,
  baseUrl: Option[String] = None
) {

  /** The llm4s provider configuration, or why there is none (a provider Szork does not know). */
  def toProviderConfig: Either[String, org.llm4s.llmconnect.config.ProviderConfig] = {
    import org.llm4s.types.ProviderModelTypes.{BaseUrl, ModelName}
    provider.toLowerCase match {
      case "openai" =>
        Right(
          org.llm4s.llmconnect.config.OpenAIConfig(
            apiKey = Secret(apiKey),
            model = ModelName(model.getOrElse(LLMConfig.DefaultOpenAIModel)),
            organization = None,
            baseUrl = BaseUrl(baseUrl.getOrElse("https://api.openai.com/v1")),
            contextWindow = 128000,
            reserveCompletion = 4096
          ))
      case "anthropic" =>
        Right(
          org.llm4s.llmconnect.config.AnthropicConfig(
            apiKey = Secret(apiKey),
            model = ModelName(model.getOrElse(LLMConfig.DefaultAnthropicModel)),
            baseUrl = BaseUrl(baseUrl.getOrElse("https://api.anthropic.com")),
            contextWindow = 200000,
            reserveCompletion = 4096
          ))
      case "llama" | "local" =>
        // Use OpenAI-compatible config for local LLaMA servers
        Right(
          org.llm4s.llmconnect.config.OpenAIConfig(
            apiKey = Secret(if (apiKey == "not-required") "sk-no-key-required" else apiKey),
            model = ModelName(model.getOrElse(LLMConfig.DefaultLlamaModel)),
            organization = None,
            baseUrl = BaseUrl(baseUrl.getOrElse("http://localhost:11434/v1")),
            contextWindow = 8192,
            reserveCompletion = 2048
          ))
      case other =>
        Left(s"Unknown LLM provider: $other")
    }
  }
}

object LLMConfig {

  /** Models used when SZORK_LLM_MODEL is not set. */
  // gpt-6-luna plays a whole adventure. OpenAI's Chat Completions API, which llm4s uses, lets its current models
  // call tools only with reasoning off, and llm4s 1.0.0 arranges that for this model (it refuses gpt-6.1-terra and
  // does not arrange it for the gpt-5.6 models)
  val DefaultOpenAIModel = "gpt-6-luna"

  /** Claude Sonnet 5.5, which plays a whole adventure at [[SzorkConfig.DefaultReasoningEffort]]. Run a playtest on a
    * model before making it this.
    */
  val DefaultAnthropicModel = "claude-sonnet-5-5"
  val DefaultLlamaModel = "llama3"

  /** The providers SZORK_LLM_PROVIDER may name. */
  val Providers: List[String] = List("openai", "anthropic", "llama", "local")
}

object SzorkConfig {
  val DefaultSessionTtlMinutes: Int = 30

  /** Where the adventure library is kept unless SZORK_ADVENTURES_DIR says otherwise. */
  val DefaultAdventuresDirectory = "adventures"

  /** Low: enough for a turn of a text adventure, and what lets a model that always reasons (the Claude 5 models, whose
    * own default is high) finish an outline within its answer. A model that does not reason is not sent an effort.
    */
  val DefaultReasoningEffort: ReasoningEffort = ReasoningEffort.Low

  // ============= Limits on what a client may send =============

  /** The longest command a player may type (or a recording may be heard as). */
  val MaxCommandChars: Int = 1000

  /** The largest recording a voice command may be, decoded: tens of seconds of speech are well under it. */
  val MaxAudioBytes: Int = 10 * 1024 * 1024

  /** The largest adventure outline, as JSON, a new game may bring with it. A designed outline is some tens of KB. */
  val MaxOutlineChars: Int = 256 * 1024

  /** The longest theme description: what the player types to validate a theme or to design an adventure. */
  val MaxThemeChars: Int = 2000

  /** The largest body an HTTP request may have (a kept adventure's outline is the largest the frontend sends). */
  val MaxRequestBytes: Long = 1024L * 1024

  /** The largest WebSocket message: a voice command of [[MaxAudioBytes]], as Base64 in its JSON, fits. A larger one
    * closes the connection.
    */
  val MaxMessageBytes: Int = 16 * 1024 * 1024

  /** How many turns a session may have running or waiting at once; a command sent beyond them is refused. */
  val MaxPendingTurns: Int = 3

  // ============= Hosts and origins =============

  /** The names this machine's loopback interface answers to. */
  val LoopbackNames: Set[String] = Set("localhost", "127.0.0.1", "::1")

  /** The browser origins allowed by default: the dev frontend (Vite, port 3090) and this server's own port. */
  def defaultOrigins(port: Int): Set[String] =
    Set(3090, port).flatMap(p => Set(s"http://localhost:$p", s"http://127.0.0.1:$p"))

  /** Whether `host` is a loopback address or name: a server bound to it is reachable only from this machine. */
  def isLoopback(host: String): Boolean = {
    val h = host.trim.toLowerCase.stripPrefix("[").stripSuffix("]")
    LoopbackNames.contains(h) || h.startsWith("127.")
  }

  /** Whether `host` means every interface. */
  def isWildcard(host: String): Boolean = Set("0.0.0.0", "::", "[::]", "*").contains(host.trim)

  /** An IPv6 address in brackets, as it appears in a Host header; other names as they are. */
  private[api] def bracketed(name: String): String =
    if (name.contains(":") && !name.startsWith("[")) s"[$name]" else name

  /** Whether a flag's value turns it off: `false`, `off`, `0` or `no`, in any case. */
  def isOff(value: String): Boolean = Set("false", "off", "0", "no").contains(value.trim.toLowerCase)

  /** A feature flag in `env`: on unless set to a value [[isOff]] accepts (an empty value leaves it on). */
  def flag(env: ConfigReader, key: String): Boolean =
    env.get(key).map(_.trim).filter(_.nonEmpty).forall(v => !isOff(v))

  /** The configuration from the environment (.env and the process environment), for the command-line tools. */
  lazy val instance: SzorkConfig = load()

  /** An LLM client for `config`'s provider. */
  def getLLMClient(config: SzorkConfig = instance): Either[String, org.llm4s.llmconnect.LLMClient] =
    config.llmConfig match {
      case Some(llm) =>
        for {
          provider <- llm.toProviderConfig
          registry <- org.llm4s.model.ModelRegistryService.default().left.map(_.message)
          client <- {
            given org.llm4s.model.ModelRegistryService = registry
            org.llm4s.llmconnect.LLMConnect.getClient(provider).left.map(_.message)
          }
        } yield client
      case None =>
        Left("No LLM configuration found")
    }

  /** The configuration in `env`. A feature flag is on unless set to `false`, `off`, `0` or `no`; a number that does not
    * parse, and an empty value, are ignored. A provider or reasoning effort that is not one Szork knows is recorded in
    * `problems`.
    */
  def load(env: ConfigReader = EnvLoader): SzorkConfig = {
    def setting(key: String): Option[String] = env.get(key).map(_.trim).filter(_.nonEmpty)
    def int(key: String): Option[Int] = setting(key).flatMap(_.toIntOption)
    def list(key: String): Option[Set[String]] = setting(key).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)

    val port = int("SZORK_PORT").getOrElse(8090)
    val (llmConfig, llmProblem) = loadLLMConfig(env).fold(problem => (None, Some(problem)), (_, None))
    val (reasoningEffort, effortProblem) = setting("SZORK_REASONING_EFFORT").map(_.toLowerCase) match {
      case None => (Some(DefaultReasoningEffort), None)
      case Some("default" | "off" | "none") => (None, None)
      case Some(name) =>
        ReasoningEffort.fromString(name) match {
          case Some(effort) => (Some(effort), None)
          case None =>
            (
              Some(DefaultReasoningEffort),
              Some(s"SZORK_REASONING_EFFORT '$name' is not one of minimal, low, medium, high, xhigh, max or default"))
        }
    }

    SzorkConfig(
      port = port,
      wsPort = int("SZORK_WS_PORT").getOrElse(9002),
      host = setting("SZORK_HOST").getOrElse("127.0.0.1"),
      // Browser origins allowed to open the WebSocket and call the API; defaults to the dev frontend and this server
      allowedOrigins = list("SZORK_ALLOWED_ORIGINS").getOrElse(defaultOrigins(port)),
      allowedHosts = list("SZORK_ALLOWED_HOSTS").getOrElse(Set.empty),
      imageGenerationEnabled = flag(env, "SZORK_IMAGE_GENERATION_ENABLED"),
      imageProvider = setting("SZORK_IMAGE_PROVIDER").map(ImageProvider.fromString).getOrElse(ImageProvider.None),
      imageModel = setting("SZORK_IMAGE_MODEL"),
      imageQuality = setting("SZORK_IMAGE_QUALITY").map(_.toLowerCase).getOrElse(SceneImages.DefaultQuality),
      imagePixels = setting("SZORK_IMAGE_PIXELS")
        .flatMap(_.toIntOption)
        .filter(_ >= 64)
        .getOrElse(SceneImages.DefaultPictureSide),
      ttsEnabled = flag(env, "SZORK_TTS_ENABLED"),
      sttEnabled = flag(env, "SZORK_STT_ENABLED"),
      musicEnabled = flag(env, "SZORK_MUSIC_ENABLED"),
      speech = SpeechConfig.load(env),
      mediaNetwork = MediaNetworkConfig.load(env),
      musicPoll = MusicPollConfig.load(env),
      llmConfig = llmConfig,
      dmContextWindow = loadDmContextWindow(env),
      inputGuardrailEnabled = flag(env, "SZORK_INPUT_GUARDRAIL"),
      reasoningEffort = reasoningEffort,
      subagents = SubagentConfig.load(env),
      autoSaveEnabled = flag(env, "SZORK_AUTO_SAVE"),
      savesDirectory = setting("SZORK_SAVES_DIR").getOrElse("szork-saves"),
      adventuresDirectory = setting("SZORK_ADVENTURES_DIR").getOrElse(DefaultAdventuresDirectory),
      cache = MediaCacheConfig.load(env),
      sessionTtlMinutes = int("SZORK_SESSION_TTL_MINUTES").filter(_ > 0).getOrElse(DefaultSessionTtlMinutes),
      problems = llmProblem.toList ++ effortProblem.toList ++ SpeechConfig.languageProblem(env).toList
    )
  }

  /** The Dungeon Master's context window: SZORK_DM_CONTEXT_MAX_MESSAGES limits each model call to that many
    * conversation messages; otherwise SZORK_DM_CONTEXT_MAX_TOKENS limits it to that many tokens (default
    * [[GameEngine.DefaultContextTokens]]; 0 or `off` sends the whole conversation). The system prompt is never counted
    * or pruned. A value that is not a positive number (other than 0/off) is ignored.
    */
  private[szork] def loadDmContextWindow(env: ConfigReader = EnvLoader): Option[ContextWindowConfig] = {
    def positive(key: String): Option[Int] =
      env.get(key).flatMap(v => v.trim.toIntOption).filter(_ > 0)
    positive("SZORK_DM_CONTEXT_MAX_MESSAGES") match {
      case Some(messages) => Some(ContextWindowConfig(maxMessages = Some(messages)))
      case None =>
        env.get("SZORK_DM_CONTEXT_MAX_TOKENS").map(_.trim.toLowerCase) match {
          case Some("0" | "off") => None
          case _ =>
            val tokens = positive("SZORK_DM_CONTEXT_MAX_TOKENS").getOrElse(GameEngine.DefaultContextTokens)
            Some(ContextWindowConfig(maxTokens = Some(tokens)))
        }
    }
  }

  /** The LLM to use, from SZORK_LLM_PROVIDER or else whichever provider's settings are present (None: none are); Left
    * when SZORK_LLM_PROVIDER names a provider Szork does not know. Blank values count as not set.
    *
    * Hand-written rather than llm4s's `Llm4sConfig.defaultProvider()`: that reads `llm4s.providers` from Typesafe
    * config, which sees neither Szork's .env file nor SZORK_LLM_PROVIDER/SZORK_LLM_MODEL, and has no "whichever key is
    * set" fallback.
    */
  private[szork] def loadLLMConfig(env: ConfigReader = EnvLoader): Either[String, Option[LLMConfig]] = {
    def setting(key: String): Option[String] = env.get(key).map(_.trim).filter(_.nonEmpty)
    val openAIKey = setting("OPENAI_API_KEY")
    val anthropicKey = setting("ANTHROPIC_API_KEY")
    val llamaUrl = setting("LLAMA_BASE_URL")
    val model = setting("SZORK_LLM_MODEL")

    def openAI = openAIKey.map(key =>
      LLMConfig("openai", key, model.orElse(Some(LLMConfig.DefaultOpenAIModel)), setting("OPENAI_BASE_URL")))
    def anthropic = anthropicKey.map(key =>
      LLMConfig("anthropic", key, model.orElse(Some(LLMConfig.DefaultAnthropicModel)), setting("ANTHROPIC_BASE_URL")))
    // Without LLAMA_BASE_URL, LLMConfig.toProviderConfig's default, which ends in /v1
    def llama = LLMConfig("llama", "not-required", model.orElse(Some(LLMConfig.DefaultLlamaModel)), llamaUrl)

    setting("SZORK_LLM_PROVIDER").map(_.toLowerCase) match {
      case Some("openai") => Right(openAI)
      case Some("anthropic") => Right(anthropic)
      case Some("llama") | Some("local") => Right(Some(llama))
      case Some(other) =>
        Left(s"SZORK_LLM_PROVIDER '$other' is not one of ${LLMConfig.Providers.mkString(", ")}")
      case None => Right(openAI.orElse(anthropic).orElse(llamaUrl.map(_ => llama)))
    }
  }

  implicit class ConfigOps(config: SzorkConfig) {

    /** Points the save directory and media cache, which are shared by everything in the process, at this config's. */
    def useStorage(): Unit = {
      StepPersistence.useSaveDirectory(Paths.get(config.savesDirectory))
      MediaCache.configure(config.cache)
    }

    /** The problems that stop the server from starting: ports it cannot listen on, settings it cannot use, and no LLM.
      */
    def validate(): SzorkResult[Unit] = {
      val errors = scala.collection.mutable.ListBuffer[String]()

      // Validate ports
      if (config.port < 1 || config.port > 65535) {
        errors += s"Invalid port: ${config.port}"
      }
      if (config.wsPort < 1 || config.wsPort > 65535) {
        errors += s"Invalid WebSocket port: ${config.wsPort}"
      }
      if (config.wsPort == config.port)
        errors += s"The WebSocket port (${config.wsPort}) equals the HTTP port: set SZORK_WS_PORT to another port"

      errors ++= config.problems

      // Validate LLM configuration (an unknown SZORK_LLM_PROVIDER is already among the problems)
      if (config.llmConfig.isEmpty && !config.problems.exists(_.startsWith("SZORK_LLM_PROVIDER"))) {
        errors += "No LLM provider configured. Set one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, or LLAMA_BASE_URL (or SZORK_LLM_PROVIDER)."
      }

      if (errors.isEmpty) {
        Right(())
      } else {
        Left(ConfigurationError(errors.mkString("\n")))
      }
    }

    /** Problems that leave the server running without a feature: an image provider chosen whose credentials are
      * missing.
      */
    def warnings: List[String] =
      if (config.imageGenerationEnabled && config.imageProvider != ImageProvider.None)
        SceneImages.config(config).left.toOption.map(reason => s"Scene images unavailable: $reason").toList
      else Nil

    def logConfiguration(logger: Logger): Unit = {
      logger.info("=== Szork Configuration ===")
      logger.info(s"Server: http://${config.host}:${config.port}, WebSocket ws://${config.host}:${config.wsPort}")
      logger.info(s"LLM Provider: ${config.llmConfig.map(_.provider).getOrElse("Not configured")}")
      val window = config.dmContextWindow.fold("whole conversation") { w =>
        w.maxMessages.fold(s"${w.maxTokens.getOrElse(0)} tokens")(m => s"$m messages")
      }
      logger.info(s"DM context window: $window")
      logger.info(s"Reasoning effort: ${config.reasoningEffort.fold("the model's default")(_.name)}")
      logger.info(s"DM input guardrail: ${if (config.inputGuardrailEnabled) "Enabled" else "Disabled"}")
      def onOff(on: Boolean) = if (on) "Enabled" else "Disabled"
      logger.info(
        s"DM subagents: NPC dialogue ${onOff(config.subagents.npcDialogue)}, hints ${onOff(config.subagents.hints)}" +
          s" (model: ${config.subagents.model.getOrElse("the Dungeon Master's")})")
      logger.info(s"Auto-save: ${if (config.autoSaveEnabled) "Enabled" else "Disabled"}")
      logger.info(s"Cache: ${if (config.cache.enabled) s"Enabled (${config.cache.directory})" else "Disabled"}")
      logger.info(s"Saves Directory: ${config.savesDirectory}")
      logger.info(s"Adventure Library: ${config.adventuresDirectory}")
      logger.info(s"Session TTL: ${config.sessionTtlMinutes} minutes")
      logger.info(s"Allowed origins: ${config.allowedOrigins.toList.sorted.mkString(", ")}")
      logger.info(s"HTTP hosts: ${config.httpHosts.fold("any (not checked)")(_.toList.sorted.mkString(", "))}")
      logger.info("========================")
    }
  }
}
