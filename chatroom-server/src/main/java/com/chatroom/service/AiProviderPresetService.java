package com.chatroom.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI provider presets with default endpoints and available models.
 * Users can select a provider or enter custom endpoint/key.
 */
@Slf4j
@Service
public class AiProviderPresetService {

    public record ProviderInfo(
            String id,
            String name,
            String defaultEndpoint,
            List<String> models,
            String defaultModel,
            String description
    ) {}

    private final List<ProviderInfo> providers;

    public AiProviderPresetService() {
        providers = List.of(
            new ProviderInfo(
                "deepseek", "DeepSeek",
                "https://api.deepseek.com/v1/chat/completions",
                List.of("deepseek-chat", "deepseek-reasoner"),
                "deepseek-chat",
                "国产高性价比，兼容OpenAI格式"
            ),
            new ProviderInfo(
                "kimi", "Kimi (月之暗面)",
                "https://api.moonshot.cn/v1/chat/completions",
                List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
                "moonshot-v1-8k",
                "长文本处理能力强，128K上下文"
            ),
            new ProviderInfo(
                "qwen", "通义千问 (Qwen)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                List.of("qwen-turbo", "qwen-plus", "qwen-max", "qwen-max-longcontext"),
                "qwen-plus",
                "阿里云出品，多规格可选"
            ),
            new ProviderInfo(
                "mimo", "Mimo (小米)",
                "https://api.xiaomimimo.com/v1/chat/completions",
                List.of("mimo-chat", "mimo-pro"),
                "mimo-chat",
                "小米AI助手，轻量快捷"
            ),
            new ProviderInfo(
                "gpt", "OpenAI GPT",
                "https://api.openai.com/v1/chat/completions",
                List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
                "gpt-4o-mini",
                "OpenAI官方API，兼容性最好"
            ),
            new ProviderInfo(
                "zhipu", "智谱GLM",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                List.of("glm-4-plus", "glm-4-flash", "glm-4-air"),
                "glm-4-flash",
                "清华智谱，国产大模型"
            ),
            new ProviderInfo(
                "custom", "自定义",
                "",
                List.of(),
                "",
                "手动输入API地址、模型和Key"
            )
        );
    }

    public List<ProviderInfo> getAllProviders() {
        return providers;
    }

    public Optional<ProviderInfo> getProvider(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Get provider display info for API response. */
    public List<Map<String, Object>> getProviderOptions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProviderInfo p : providers) {
            result.add(Map.of(
                "id", p.id(),
                "name", p.name(),
                "defaultEndpoint", p.defaultEndpoint(),
                "models", p.models(),
                "defaultModel", p.defaultModel(),
                "description", p.description()
            ));
        }
        return result;
    }
}
