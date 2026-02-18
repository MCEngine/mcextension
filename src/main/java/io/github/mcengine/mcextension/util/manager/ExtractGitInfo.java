package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;

import java.util.Map;

public final class ExtractGitInfo {
    private ExtractGitInfo() {}

    @SuppressWarnings("unchecked")
    public static MCExtensionManager.GitInfo invoke(Object gitBlock) {
        if (!(gitBlock instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        String provider = String.valueOf(map.getOrDefault("provider", "")).trim();
        String owner = String.valueOf(map.getOrDefault("owner", "")).trim();
        String repository = String.valueOf(map.getOrDefault("repository", "")).trim();
        if (provider.isEmpty() || owner.isEmpty() || repository.isEmpty()) {
            return null;
        }
        return new MCExtensionManager.GitInfo(provider, owner, repository);
    }
}
