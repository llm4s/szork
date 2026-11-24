package org.llm4s.szork.api

import org.llm4s.model.{ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService}
import org.llm4s.types.Result

/** The model registry Szork's LLM clients are built with: llm4s's bundled catalogue, with the Claude models that reject
  * a `temperature` marked as such.
  *
  * llm4s sends `CompletionOptions`' default temperature (0.7) unless the registry lists `temperature` in the model's
  * `disallowedParams` or the model is one it recognises by name, which in llm4s 0.6.0 is Claude Opus 4.7 and later
  * only. Claude Sonnet 5, Haiku 5.5, Fable and Mythos reject it too ("`temperature` is deprecated for this model"), and
  * most are newer than the catalogue, so they are marked here through llm4s's registry override. Remove this once llm4s
  * recognises them itself.
  */
object SzorkModelRegistry {

  private val Temperature = "temperature"

  def load(): Result[ModelRegistryService] = ModelRegistryService.default().map(new WithoutTemperature(_))

  /** Whether `model` is a Claude model that rejects `temperature` and that llm4s does not recognise as one. */
  private[api] def rejectsTemperature(model: String): Boolean = {
    val name = model.toLowerCase
    name.contains("claude-fable") || name.contains("claude-mythos") ||
    majorVersion(name, "claude-sonnet-").exists(_ >= 5) || majorVersion(name, "claude-haiku-").exists(_ >= 5)
  }

  /** The version after `family` in `name`: 5 for `claude-sonnet-5-5`; none for a date (`claude-sonnet-4-20250514` is
    * version 4) or a name without the family.
    */
  private def majorVersion(name: String, family: String): Option[Int] = {
    val at = name.indexOf(family)
    if (at < 0) None
    else {
      val digits = name.substring(at + family.length).takeWhile(_.isDigit)
      Option.when(digits.nonEmpty && digits.length <= 2)(digits.toInt)
    }
  }

  private def marked(model: ModelMetadata): ModelMetadata =
    model.withCapabilities(
      model.capabilities.withDisallowedParams(model.capabilities.disallowedParams.getOrElse(Set.empty) + Temperature))

  /** What is known of a model the catalogue does not have: only that it rejects a temperature. */
  private def unlisted(modelId: String): ModelMetadata =
    ModelMetadata(
      modelId = modelId,
      provider = "anthropic",
      mode = ModelMode.Chat,
      maxInputTokens = None,
      maxOutputTokens = None,
      inputCostPerToken = None,
      outputCostPerToken = None,
      capabilities = ModelCapabilities(disallowedParams = Some(Set(Temperature))),
      pricing = ModelPricing(),
      deprecationDate = None
    )

  final private class WithoutTemperature(base: ModelRegistryService) extends ModelRegistryService {

    private def resolve(modelId: String, found: Result[ModelMetadata]): Result[ModelMetadata] =
      if (rejectsTemperature(modelId)) Right(found.fold(_ => unlisted(modelId.trim), marked)) else found

    override def lookup(modelId: String): Result[ModelMetadata] = resolve(modelId, base.lookup(modelId))

    override def lookup(provider: String, modelName: String): Result[ModelMetadata] =
      resolve(modelName, base.lookup(provider, modelName))

    override def findByCapability(capability: String): Result[List[ModelMetadata]] = base.findByCapability(capability)
    override def listProviders(): Result[List[String]] = base.listProviders()
    override def listByMode(mode: ModelMode): Result[List[ModelMetadata]] = base.listByMode(mode)
    override def listByProvider(provider: String): Result[List[ModelMetadata]] = base.listByProvider(provider)
    override def statistics(): Result[Map[String, Any]] = base.statistics()
  }
}
