import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** 删除外部嵌套类型的重复编译元数据；保留自身、子类声明及全部 JVM 访问关系。 */
public final class StripExternalInnerClasses {
    public static void main(String[] args)throws Exception {
        Path archive=Path.of(args[0]),temporary=archive.resolveSibling(archive.getFileName()+".metadata");
        try(ZipFile input=new ZipFile(archive.toFile());ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(temporary))) {
            for(var entry:input.stream().toList()) {
                byte[] bytes=input.getInputStream(entry).readAllBytes();
                if(entry.getName().endsWith(".class")) {
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(0);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        @Override public void visitInnerClass(String name,String outer,String simple,int access) {
                            if(name.equals(reader.getClassName()) || reader.getClassName().equals(outer))super.visitInnerClass(name,outer,simple,access);
                        }
                    },0);
                    bytes=writer.toByteArray();
                }
                ZipEntry target=new ZipEntry(entry.getName());target.setTime(entry.getTime());output.putNextEntry(target);output.write(bytes);output.closeEntry();
            }
        }
        Files.move(temporary,archive,StandardCopyOption.REPLACE_EXISTING);
    }
}
