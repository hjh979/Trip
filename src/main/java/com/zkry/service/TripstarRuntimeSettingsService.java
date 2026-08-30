package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.constant.TripstarSettingKeys;
import com.zkry.domain.entity.SystemRuntimeSetting;
import com.zkry.mapper.SystemRuntimeSettingMapper;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Loads encrypted runtime settings; secrets are redacted from public snapshots. */
@Service
public class TripstarRuntimeSettingsService {
    private static final Logger log = LoggerFactory.getLogger(TripstarRuntimeSettingsService.class);
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        TripstarSettingKeys.AMAP_WEB_KEY,
        TripstarSettingKeys.AMAP_SECURITY_JS_CODE,
        TripstarSettingKeys.OPENAI_API_KEY,
        TripstarSettingKeys.EMBEDDING_API_KEY
    );

    private final Map<String, Object> settings = new LinkedHashMap<>();
    private final SystemRuntimeSettingMapper settingMapper;
    private final RuntimeSecretCryptoService cryptoService;

    public TripstarRuntimeSettingsService(
        @Value("${tripstar.map.amap.key:}") String amapWebKey,
        @Value("${tripstar.map.amap.js-security-code:}") String amapSecurityJsCode,
        @Value("${tripstar.ai.provider:dashscope}") String aiProvider,
        @Value("${tripstar.ai.api-key:}") String aiApiKey,
        @Value("${tripstar.ai.base-url:}") String aiBaseUrl,
        @Value("${tripstar.ai.model:qwen-plus}") String aiModel,
        @Value("${tripstar.rag.embedding.api-key:}") String embeddingApiKey,
        @Value("${tripstar.rag.embedding.base-url:}") String embeddingBaseUrl,
        @Value("${tripstar.rag.embedding.model:qllama/bge-small-zh-v1.5}") String embeddingModel,
        @Value("${tripstar.rag.milvus.dimensions:512}") Integer embeddingDimensions,
        SystemRuntimeSettingMapper settingMapper,
        RuntimeSecretCryptoService cryptoService
    ) {
        this.settingMapper = settingMapper;
        this.cryptoService = cryptoService;
        settings.put(TripstarSettingKeys.AI_PROVIDER, value(aiProvider));
        settings.put(TripstarSettingKeys.AMAP_WEB_KEY, value(amapWebKey));
        settings.put(TripstarSettingKeys.AMAP_WEB_JS_KEY, "");
        settings.put(TripstarSettingKeys.AMAP_SECURITY_JS_CODE, value(amapSecurityJsCode));
        settings.put(TripstarSettingKeys.GOOGLE_MAPS_API_KEY, "");
        settings.put(TripstarSettingKeys.GOOGLE_MAPS_PROXY, "");
        settings.put(TripstarSettingKeys.OPENAI_API_KEY, value(aiApiKey));
        settings.put(TripstarSettingKeys.OPENAI_BASE_URL, value(aiBaseUrl));
        settings.put(TripstarSettingKeys.OPENAI_MODEL, value(aiModel));
        settings.put(TripstarSettingKeys.EMBEDDING_API_KEY, value(embeddingApiKey));
        settings.put(TripstarSettingKeys.EMBEDDING_BASE_URL, value(embeddingBaseUrl));
        settings.put(TripstarSettingKeys.EMBEDDING_MODEL, value(embeddingModel));
        settings.put(TripstarSettingKeys.EMBEDDING_DIMENSIONS, embeddingDimensions == null ? 512 : embeddingDimensions);
    }

    @PostConstruct
    public synchronized void loadPersistedSettings() {
        try {
            for (SystemRuntimeSetting persisted : settingMapper.selectList(Wrappers.lambdaQuery())) {
                if (!settings.containsKey(persisted.getSettingKey())) continue;
                String decrypted = cryptoService.decrypt(persisted.getEncryptedValue());
                settings.put(persisted.getSettingKey(), TripstarSettingKeys.EMBEDDING_DIMENSIONS.equals(persisted.getSettingKey())
                    ? Integer.parseInt(decrypted) : decrypted);
            }
            log.info("[Settings] loaded encrypted runtime settings count={}", settings.size());
        } catch (RuntimeException ex) {
            log.warn("[Settings] persisted settings unavailable; using environment values reason={}", ex.getMessage());
        }
    }

    public synchronized Map<String, Object> snapshot() { return new LinkedHashMap<>(settings); }

    public synchronized Map<String, Object> publicSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>(settings);
        for (String key : SENSITIVE_KEYS) {
            Object value = result.get(key);
            result.put(key, "");
            result.put(key + "_configured", value != null && !String.valueOf(value).isBlank());
        }
        result.put("runtime_encryption_configured", cryptoService.productionKeyConfigured());
        return result;
    }

    public synchronized void update(Map<String, Object> updates) { update(updates, null); }

    public synchronized void update(Map<String, Object> updates, Long updatedBy) {
        if (updates == null) return;
        updates.forEach((key, raw) -> {
            if (!settings.containsKey(key)) return;
            if (SENSITIVE_KEYS.contains(key) && (raw == null || String.valueOf(raw).isBlank())) return;
            Object normalized = normalize(key, raw);
            settings.put(key, normalized);
            persist(key, normalized, updatedBy);
        });
    }

    public synchronized Optional<String> stringValue(String key) {
        Object value = settings.get(key);
        if (value == null || String.valueOf(value).isBlank()) return Optional.empty();
        return Optional.of(String.valueOf(value).trim());
    }

    public synchronized boolean hasText(String key) { return stringValue(key).isPresent(); }

    private void persist(String key, Object value, Long updatedBy) {
        SystemRuntimeSetting setting = findPersisted(key).orElseGet(SystemRuntimeSetting::new);
        setting.setSettingKey(key);
        setting.setEncryptedValue(cryptoService.encrypt(String.valueOf(value)));
        setting.setUpdatedBy(updatedBy);
        setting.setStatus("CONFIGURED");
        if (setting.getLastError() == null) setting.setLastError("");
        if (setting.getId() == null) settingMapper.insert(setting); else settingMapper.updateById(setting);
    }

    private Optional<SystemRuntimeSetting> findPersisted(String key) {
        return Optional.ofNullable(settingMapper.selectOne(Wrappers.<SystemRuntimeSetting>lambdaQuery()
            .eq(SystemRuntimeSetting::getSettingKey, key).last("LIMIT 1")));
    }

    private Object normalize(String key, Object value) {
        if (TripstarSettingKeys.EMBEDDING_DIMENSIONS.equals(key)) {
            try { return Math.max(2, Integer.parseInt(String.valueOf(value))); }
            catch (NumberFormatException ignored) { return 512; }
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String value(String value) { return value == null ? "" : value; }
}
