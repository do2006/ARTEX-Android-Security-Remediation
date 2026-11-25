import java.nio.file.Files;
import java.nio.file.Path;

/** Safe source check for password references in the APK signing configuration. */
public final class BuildSigningConfigTest {
    public static void main(String[] args) throws Exception {
        String script = Files.readString(Path.of("scripts/build_apk.sh"));
        check(script.contains("export ARTEX_KS_PASS ARTEX_KEY_PASS"));
        check(script.contains("--ks-pass \"env:ARTEX_KS_PASS\""));
        check(script.contains("--key-pass \"env:ARTEX_KEY_PASS\""));
        check(!script.contains("--ks-pass \"$KS_PASS\""));
        check(!script.contains("--key-pass \"$KEY_PASS\""));
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError("signing password source must remain environment based");
    }
}
