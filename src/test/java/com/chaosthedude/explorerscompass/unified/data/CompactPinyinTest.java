package com.chaosthedude.explorerscompass.unified.data;
import com.github.promeg.pinyinhelper.Pinyin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CompactPinyinTest {
    @Test void allUtf16CharactersMatchOriginalLibrary() {
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            char character = (char) code;
            assertEquals(Pinyin.isChinese(character), CompactPinyin.isChinese(character), "Flag U+" + Integer.toHexString(code));
            assertEquals(Pinyin.toPinyin(character), CompactPinyin.toPinyin(character), "Pinyin U+" + Integer.toHexString(code));
        }
    }
}
