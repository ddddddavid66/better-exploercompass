package com.chaosthedude.explorerscompass.unified.data;

/** 中文语言会继承英文资源；英文回退不能误当成已有中文名称。 */
public final class LocalizedNames {
    private LocalizedNames() {}
    public static boolean usable(String translated, boolean chinese) {
        return translated != null && !translated.isBlank() && (!chinese || translated.codePoints()
                .anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN));
    }
}
