package com.artex.mobile;

import java.net.URL;
import java.util.Locale;

/** Small, Android-independent policy helpers for untrusted catalog metadata. */
final class SecurityPolicy {
    private SecurityPolicy() {}

    static boolean isSafeFileName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                && !name.equals(".") && !name.equals("..");
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    static boolean supportsArm64Runtime(String[] supportedAbis) {
        if (supportedAbis == null) return false;
        for (String abi : supportedAbis) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    static boolean usesNativeArm64Runtime(String[] supportedAbis) {
        return supportedAbis != null && supportedAbis.length > 0
                && "arm64-v8a".equals(supportedAbis[0]);
    }

    static String[] translatedArm64PipOptions(String[] supportedAbis) {
        if (!supportsArm64Runtime(supportedAbis) || usesNativeArm64Runtime(supportedAbis)) {
            return new String[0];
        }
        return new String[]{"--only-binary=:all:", "--platform=android_aarch64",
                "--platform=android_24_aarch64", "--python-version=3.14",
                "--implementation=cp", "--abi=cp314", "--abi=cp36-abi3",
                "--abi=cp37-abi3"};
    }

    static boolean isAllowedDownloadUrl(String value) {
        return isAllowedToolReleaseUrl(value, null) || isAllowedSkillReleaseUrl(value, null);
    }

    static boolean isAllowedCatalogUrl(String value, String catalogName) {
        return value != null && value.equals(catalogUrl(catalogName));
    }

    static String catalogUrl(String catalogName) {
        if (!"tools".equals(catalogName) && !"skills".equals(catalogName)) {
            throw new IllegalArgumentException("Unknown catalog");
        }
        return "https://raw.githubusercontent.com/Srysfu/artex-mobile-tools/"
                + "c1fa82a2507be24c2f3f219137f74b7832812e7d/" + catalogName + ".json";
    }

    static boolean isAllowedToolReleaseUrl(String value, String name) {
        return isAllowedReleaseUrl(value, name == null ? null : "tools-v2/" + name);
    }

    static boolean isAllowedSkillReleaseUrl(String value, String name) {
        return isAllowedReleaseUrl(value, name == null ? null : "tools-v2/skill-" + name + ".zip");
    }

    private static boolean isAllowedReleaseUrl(String value, String expectedSuffix) {
        try {
            URL url = new URL(value);
            if (!isHttps443(url) || !"github.com".equalsIgnoreCase(url.getHost())) return false;
            String prefix = "/Srysfu/artex-mobile-tools/releases/download/";
            if (!url.getPath().startsWith(prefix) || url.getQuery() != null || url.getRef() != null) return false;
            return expectedSuffix == null || url.getPath().equals(prefix + expectedSuffix);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isAllowedReleaseRedirectUrl(String value) {
        try {
            URL url = new URL(value);
            if (!isHttps443(url) || url.getPath() == null || url.getPath().isEmpty()) return false;
            String host = url.getHost().toLowerCase(Locale.US);
            return host.equals("objects.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isHttps443(URL url) {
        return "https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443);
    }
}
