import com.github.promeg.pinyinhelper.Pinyin;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Apache-2.0 字音数据的确定性导出工具；运行时不需要原库或词典引擎。 */
public final class GeneratePinyinTable {
    public static void main(String[] args) throws Exception {
        var syllables = new LinkedHashMap<String, Integer>();
        syllables.put("", 0);
        int minimum = 65536, maximum = 0;
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            if (!Pinyin.isChinese((char) code)) continue;
            minimum = Math.min(minimum, code); maximum = code;
            syllables.computeIfAbsent(Pinyin.toPinyin((char) code), ignored -> syllables.size());
        }
        Path output = Path.of(args[0]); Files.createDirectories(output.getParent());
        try (var stream = new DataOutputStream(Files.newOutputStream(output))) {
            stream.writeInt(0x50494E32); stream.writeInt(minimum); stream.writeInt(maximum);
            stream.writeInt(syllables.size());
            for (String syllable : syllables.keySet()) stream.writeUTF(syllable);
            if(syllables.size()>512)throw new IllegalStateException("Pinyin index exceeds nine bits");
            // 408 个音节只需 9 位；值不变，减少发布资源占用。
            int word=0,bits=0;
            for(int code=minimum;code<=maximum;code++) {
                word=(word<<9)|(Pinyin.isChinese((char)code)?syllables.get(Pinyin.toPinyin((char)code)):0);bits+=9;
                while(bits>=8){bits-=8;stream.writeByte(word>>>bits);}
            }
            if(bits>0)stream.writeByte(word<<(8-bits));
        }
        System.out.println("Pinyin table: " + syllables.size() + " syllables, " + Files.size(output) + " bytes");
    }
}
