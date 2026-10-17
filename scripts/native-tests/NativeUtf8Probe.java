import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

public class NativeUtf8Probe {
    private static native byte[] bytes(String value);
    private static native boolean pathAccepted(String value);

    private static void verify(String name, String value) {
        byte[] expected = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        byte[] actual = bytes(value);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + HexFormat.of().formatHex(expected)
                + ", actual " + (actual == null ? "null" : HexFormat.of().formatHex(actual)));
        }
        boolean validPath = value != null && value.indexOf(0) < 0;
        if (pathAccepted(value) != validPath) {
            throw new AssertionError(name + ": path admission mismatch");
        }
    }

    public static void main(String[] args) {
        System.load(args[0]);
        String[] names = {"ascii", "bmp", "emoji", "supplementaryHan", "embeddedNul",
            "empty", "null", "unpairedHigh", "unpairedLow", "mixedPath"};
        String[] values = {"hello", new String(new char[]{0x4E2D, 0x6587}),
            new String(Character.toChars(0x1F642)), new String(Character.toChars(0x20000)),
            new String(new char[]{'a', 0, 'b'}), "", null,
            new String(new char[]{0xD800}), new String(new char[]{0xDC00}),
            "/storage/" + new String(Character.toChars(0x20000)) + "/model.gguf"};
        for (int i = 0; i < values.length; i++) verify(names[i], values[i]);

        // Include arbitrary Java UTF-16, not only well-formed supplementary pairs.
        Random random = new Random(0xA607A);
        for (int i = 0; i < 2_000; i++) {
            char[] chars = new char[random.nextInt(96)];
            for (int j = 0; j < chars.length; j++) chars[j] = (char) random.nextInt(65_536);
            verify("random-" + i, new String(chars));
        }
        // Repeated calls under -Xcheck:jni exercise local-reference cleanup.
        for (int i = 0; i < 10_000; i++) verify("repeat-" + i, values[i % values.length]);
        System.out.println("{\"fixedCases\":10,\"randomCases\":2000,\"repeatedCases\":10000,"
            + "\"textAndPathChecks\":24020,\"failures\":0}");
    }
}
