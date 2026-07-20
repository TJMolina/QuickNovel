package com.lagradost.quicknovel.util.translation.models
import com.fasterxml.jackson.annotation.JsonProperty

data class GeminiTranslationResponse(
    @JsonProperty("error") val error: GeminiErrorResponse? = null,
    @JsonProperty("candidates") val candidates: List<Candidate>? = null,
    @JsonProperty("promptFeedback") val promptFeedback: PromptFeedback? = null
)
data class Candidate(
    @JsonProperty("content") val content: Content? = null,
    @JsonProperty("finishReason") val finishReason: String? = null
)

data class Content(@JsonProperty("parts") val parts: List<Part>? = null)
data class Part(@JsonProperty("text") val text: String? = null)
data class PromptFeedback(@JsonProperty("blockReason") val blockReason: String? = null)



data class GeminiErrorResponse(
    @JsonProperty("code") val code: Int,
    @JsonProperty("message") val message: String,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("details") val details: List<GeminiErrorDetail>? = null
)


data class GeminiErrorDetail(
    @JsonProperty("retryDelay") val retryDelay: String? = null,
)