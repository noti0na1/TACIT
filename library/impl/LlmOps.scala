package library

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams

class LlmOps(config: Option[LlmConfig]):

  private lazy val client: OpenAIClient =
    config match
      case None =>
        throw RuntimeException("LLM is not configured. Pass --llm-base-url, --llm-api-key, --llm-model or use a config file.")
      case Some(cfg) =>
        OpenAIOkHttpClient.builder()
          .apiKey(cfg.apiKey)
          .baseUrl(cfg.baseUrl)
          .build()

  def chat(message: String): String =
    val cfg = config.getOrElse:
      throw RuntimeException("LLM is not configured. Pass --llm-base-url, --llm-api-key, --llm-model or use a config file.")
    val params = ChatCompletionCreateParams.builder()
      .model(cfg.model)
      .addUserMessage(message)
      .build()
    val completion = client.chat().completions().create(params)
    completion.choices().get(0).message().content().orElse("").nn

  def chat(message: Classified[String]): Classified[String] =
    ClassifiedImpl.wrap(chat(ClassifiedImpl.unwrap(message)))
