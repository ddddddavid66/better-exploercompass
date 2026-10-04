package com.chaosthedude.explorerscompass.unified.data;


import java.text.Normalizer;
import java.util.Locale;

/** 只在群系列表构建时转拼音，渲染和按键匹配不重复转换。 */
public record SearchText(String name, String id, String pinyin, String initials) {
    public static SearchText of(String name, String id) {
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (CompactPinyin.isChinese(character)) {
                String syllable = CompactPinyin.toPinyin(character);
                full.append(syllable);
                initials.append(syllable.charAt(0));
            } else {
                full.append(character);
                if (Character.isLetterOrDigit(character)) initials.append(character);
            }
        }
        return new SearchText(normalize(name), normalize(id), normalize(full.toString()),
                normalize(initials.toString()));
    }

    public boolean matches(String query) {
        String normalized = normalize(query);
        return name.contains(normalized) || id.contains(normalized)
                || pinyin.contains(normalized) || initials.contains(normalized);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }
}
