package com.chaosthedude.explorerscompass.unified.data;

import java.io.DataInputStream;
import java.io.IOException;

/** 单字读音与 TinyPinyin 2.0.3 相同；直接读取汉字索引，不需要词典或网络。 */
public final class CompactPinyin {
    private static final int MINIMUM;
    private static final String[] SYLLABLES;
    private static final char[] INDEX;
    static {
        try (var input = CompactPinyin.class.getResourceAsStream("/assets/explorerscompass/pinyin.bin")) {
            if (input == null) throw new IOException("Missing pinyin table");
            var stream = new DataInputStream(input);
            if (stream.readInt() != 0x50494E31) throw new IOException("Invalid pinyin table");
            MINIMUM = stream.readInt(); int maximum = stream.readInt(); int size = stream.readInt();
            if (MINIMUM < 0 || maximum > 65535 || maximum < MINIMUM || size < 1 || size > 65535)
                throw new IOException("Invalid pinyin table bounds");
            SYLLABLES = new String[size];
            for (int i = 0; i < size; i++) SYLLABLES[i] = stream.readUTF();
            INDEX = new char[maximum - MINIMUM + 1];
            for (int i = 0; i < INDEX.length; i++) {
                INDEX[i] = stream.readChar();
                if (INDEX[i] >= size) throw new IOException("Invalid syllable index");
            }
            if (stream.read() != -1) throw new IOException("Unexpected pinyin table data");
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    private CompactPinyin() {}
    public static boolean isChinese(char character) {
        int offset = character - MINIMUM;
        return offset >= 0 && offset < INDEX.length && INDEX[offset] != 0;
    }
    public static String toPinyin(char character) {
        return isChinese(character) ? SYLLABLES[INDEX[character - MINIMUM]] : Character.toString(character);
    }
}
