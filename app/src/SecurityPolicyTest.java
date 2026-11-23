package com.artex.mobile;

/** Safe host-JVM regression checks; run with: java com.artex.mobile.SecurityPolicyTest */
public final class SecurityPolicyTest {
    public static void main(String[] args) {
        check(SecurityPolicy.isSafeFileName("nuclei-3.2.0"));
        check(!SecurityPolicy.isSafeFileName("../outside"));
        check(!SecurityPolicy.isSafeFileName("folder/tool"));
        check(SecurityPolicy.isSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        check(!SecurityPolicy.isSha256("not-a-digest"));
        check(SecurityPolicy.isAllowedCatalogUrl(SecurityPolicy.catalogUrl("tools"), "tools"));
        check(!SecurityPolicy.isAllowedCatalogUrl("https://raw.githubusercontent.com/Srysfu/artex-mobile-tools/main/tools.json", "tools"));
        check(SecurityPolicy.isAllowedToolReleaseUrl("https://github.com/Srysfu/artex-mobile-tools/releases/download/tools-v2/nuclei", "nuclei"));
        check(SecurityPolicy.isAllowedSkillReleaseUrl("https://github.com/Srysfu/artex-mobile-tools/releases/download/tools-v2/skill-network-offensive.zip", "network-offensive"));
        check(!SecurityPolicy.isAllowedToolReleaseUrl("https://github.com/Srysfu/other/releases/download/tools-v2/nuclei", "nuclei"));
        check(!SecurityPolicy.isAllowedToolReleaseUrl("https://github.com:444/Srysfu/artex-mobile-tools/releases/download/tools-v2/nuclei", "nuclei"));
        check(!SecurityPolicy.isAllowedToolReleaseUrl("https://github.com/Srysfu/artex-mobile-tools/releases/download/tools-v2/other", "nuclei"));
        check(SecurityPolicy.isAllowedReleaseRedirectUrl("https://objects.githubusercontent.com/release-asset"));
        check(!SecurityPolicy.isAllowedReleaseRedirectUrl("https://evil.example/release-asset"));
        check(!SecurityPolicy.isAllowedReleaseRedirectUrl("https://objects.githubusercontent.com:444/release-asset"));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("security policy regression");
    }
}
