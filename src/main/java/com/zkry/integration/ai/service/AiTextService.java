package com.zkry.integration.ai.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.zkry.service.TripstarRuntimeSettingsService;
import com.zkry.integration.ExternalCallBulkheads;
import com.zkry.common.constant.TripstarSettingKeys;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiTextService {

    private static final Logger log = LoggerFactory.getLogger(AiTextService.class);
    private final TripstarRuntimeSettingsService runtimeSettingsService;
    private final ExternalCallBulkheads bulkheads;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxOutputTokens;
    private final boolean deepseekThinkingEnabled;

    public AiTextService(
        TripstarRuntimeSettingsService runtimeSettingsService,
        ExternalCallBulkheads bulkheads,
        @Value("${tripstar.ai.connect-timeout-seconds:15}") long connectTimeoutSeconds,
        @Value("${tripstar.ai.read-timeout-seconds:150}") long readTimeoutSeconds,
        @Value("${tripstar.ai.max-output-tokens:6000}") int maxOutputTokens,
        @Value("${tripstar.ai.deepseek-thinking-enabled:false}") boolean deepseekThinkingEnabled
    ) {
        this.runtimeSettingsService = runtimeSettingsService;
        this.bulkheads = bulkheads;
        this.connectTimeout = Duration.ofSeconds(Math.max(3, Math.min(connectTimeoutSeconds, 60)));
        this.readTimeout = Duration.ofSeconds(Math.max(30, Math.min(readTimeoutSeconds, 300)));
        this.maxOutputTokens = Math.max(2000, Math.min(maxOutputTokens, 12000));
        this.deepseekThinkingEnabled = deepseekThinkingEnabled;
    }

    public boolean isAvailable() {
        boolean available = runtimeSettingsService.hasText(TripstarSettingKeys.OPENAI_API_KEY);
        log.debug("[AI] 运行时 AI 配置可用性检查 available={}", available);
        return available;
    }

    /**
     * 统一封装 DashScope 与 OpenAI 兼容提供商的文本生成调用。
     *
     * <p>业务层只关心“有没有生成出文本”，失败原因在这里记录日志并返回 Optional.empty()。
     * 日志只记录长度和耗时，不打印完整 prompt，避免把用户输入或密钥相关上下文写进控制台。
     */
    public Optional<String> generate(String systemPrompt, String userPrompt) {
        Optional<ChatModel> chatModel = chatModel();
        if (chatModel.isEmpty()) {
            log.info("[AI] AI Key 未配置，跳过 LLM 调用 systemPromptLength={} userPromptLength={}",
                length(systemPrompt), length(userPrompt));
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        log.info("[AI] 开始调用模型 systemPromptLength={} userPromptLength={} modelClass={}",
            length(systemPrompt), length(userPrompt), chatModel.get().getClass().getSimpleName());
        try {
            String content = bulkheads.executeUnchecked(
                ExternalCallBulkheads.Provider.AI,
                () -> ChatClient.create(chatModel.get())
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content()
            );
            if (content == null || content.isBlank()) {
                log.warn("[AI] 模型返回空内容 elapsedMs={}", System.currentTimeMillis() - startedAt);
                return Optional.empty();
            }
            log.info("[AI] 模型调用成功 responseLength={} elapsedMs={}",
                content.length(), System.currentTimeMillis() - startedAt);
            return Optional.of(content.trim());
        } catch (Exception ex) {
            log.warn("[AI] 模型调用失败 elapsedMs={} reason={}",
                System.currentTimeMillis() - startedAt, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    public Optional<ChatModel> chatModel() {
        return chatModel("");
    }

    /**
     * Creates a model for a latency-sensitive workflow without changing the administrator's
     * global model selection. A blank override keeps the persisted/default model.
     */
    public Optional<ChatModel> chatModel(String modelOverride) {
        Optional<String> apiKey = runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_API_KEY);
        if (apiKey.isEmpty()) {
            return Optional.empty();
        }
        String model = modelOverride == null || modelOverride.isBlank()
            ? runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_MODEL).orElse("qwen-plus")
            : modelOverride.trim();
        String baseUrl = runtimeSettingsService.stringValue(TripstarSettingKeys.OPENAI_BASE_URL).orElse("");
        String provider = runtimeSettingsService.stringValue(TripstarSettingKeys.AI_PROVIDER)
            .orElseGet(() -> baseUrl.isBlank() ? "dashscope" : "openai-compatible");
        try {
            return Optional.of(isOpenAiCompatible(provider)
                ? openAiCompatibleModel(apiKey.get(), baseUrl, model, provider)
                : dashScopeModel(apiKey.get(), baseUrl, model));
        } catch (Exception ex) {
            log.warn("[AI] 运行时 ChatModel 创建失败 provider={} reason={}", provider, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    public boolean supportsImageInput() {
        String provider = runtimeSettingsService.stringValue(TripstarSettingKeys.AI_PROVIDER).orElse("dashscope");
        return !isOpenAiCompatible(provider);
    }

    private ChatModel dashScopeModel(String apiKey, String baseUrl, String model) {
        DashScopeApi.Builder apiBuilder = DashScopeApi.builder()
            .apiKey(apiKey)
            .restClientBuilder(restClientBuilder());
        if (!baseUrl.isBlank()) {
            apiBuilder.baseUrl(baseUrl);
        }
        DashScopeChatOptions options = DashScopeChatOptions.builder()
            .model(model)
            .maxToken(maxOutputTokens)
            .temperature(0.2D)
            .parallelToolCalls(true)
            .multiModel(true)
            .build();
        return DashScopeChatModel.builder()
            .dashScopeApi(apiBuilder.build())
            .defaultOptions(options)
            .build();
    }

    private ChatModel openAiCompatibleModel(
        String apiKey,
        String baseUrl,
        String model,
        String provider
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI 兼容提供商必须配置 AI_BASE_URL");
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .restClientBuilder(restClientBuilder())
            .build();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .temperature(0.2D)
            .parallelToolCalls(true);
        if (isDeepseek(provider, baseUrl, model)) {
            // DeepSeek V4 defaults to high-effort thinking, and Agent-style prompts may be
            // promoted to max effort. Travel planning already has deterministic research,
            // tools and validation, so online requests use non-thinking mode by default.
            // Keep this configurable for offline/high-quality workflows.
            optionsBuilder.extraBody(Map.of(
                "thinking",
                Map.of("type", deepseekThinkingEnabled ? "enabled" : "disabled")
            ));
        }
        OpenAiChatOptions options = optionsBuilder.build();
        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .build();
    }

    private RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    private boolean isOpenAiCompatible(String provider) {
        return provider != null && (
            provider.equalsIgnoreCase("deepseek")
                || provider.equalsIgnoreCase("openai")
                || provider.equalsIgnoreCase("openai-compatible")
        );
    }

    private boolean isDeepseek(String provider, String baseUrl, String model) {
        return (provider != null && provider.equalsIgnoreCase("deepseek"))
            || (baseUrl != null && baseUrl.toLowerCase().contains("deepseek"))
            || (model != null && model.toLowerCase().startsWith("deepseek"));
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
