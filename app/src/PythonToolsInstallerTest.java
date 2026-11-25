package com.artex.mobile;

/** Host-JVM checks for the ABI gate; does not invoke the bundled Python runtime. */
public final class PythonToolsInstallerTest {
    public static void main(String[] args) {
        check(SecurityPolicy.supportsArm64Runtime(new String[]{"arm64-v8a", "armeabi-v7a"}));
        check(SecurityPolicy.supportsArm64Runtime(new String[]{"x86_64", "arm64-v8a"}));
        check(!SecurityPolicy.supportsArm64Runtime(new String[]{"x86"}));
        check(!SecurityPolicy.supportsArm64Runtime(new String[0]));
        check(!SecurityPolicy.supportsArm64Runtime(null));
        check(SecurityPolicy.usesNativeArm64Runtime(new String[]{"arm64-v8a", "x86_64"}));
        check(!SecurityPolicy.usesNativeArm64Runtime(new String[]{"x86_64", "arm64-v8a"}));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Python runtime ABI gate regression");
    }
}
