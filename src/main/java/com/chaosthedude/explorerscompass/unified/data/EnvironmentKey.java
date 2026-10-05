package com.chaosthedude.explorerscompass.unified.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class EnvironmentKey {
    private EnvironmentKey() {}

    public static String of(String account, String kind, String address) {
        // 长度前缀防止地址里的分隔符造成不同环境碰撞。
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        String value = account.length() + ":" + account + kind.length() + ":" + kind
                + normalized.length() + ":" + normalized;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 17 must provide SHA-256", exception);
        }
    }
}
