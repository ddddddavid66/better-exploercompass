package com.chaosthedude.explorerscompass.benchmark;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.ToolProvider;

/** 外部 jar 的隔离 API 桥；旧类与新类同名时使用独立类加载器，不把算法导入源码。 */
final class ExternalApiBridge implements AutoCloseable {
    private final Path input, directory;
    private final byte[] sha;
    private final URLClassLoader loader;
    ExternalApiBridge(String engine,String className,String source,Class<?> parentApi) throws Exception {
        String property=engine.equals("unified105")?"compassbench.baselineJar":"compassbench.enhanceJar";
        input=Path.of(System.getProperty(property));sha=digest(input);
        directory=Files.createTempDirectory("compassbench-api-");
        Path copy=directory.resolve("input.jar");Files.copy(input,copy);
        Path sourceFile=directory.resolve(className.replace('.','/')+".java");Files.createDirectories(sourceFile.getParent());Files.writeString(sourceFile,source);
        String classpath=copy+java.io.File.pathSeparator+System.getProperty("java.class.path");
        int status=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-implicit:none","-d",directory.toString(),"-encoding","UTF-8","-classpath",classpath,sourceFile.toString());
        if(status!=0)throw new IllegalStateException("External API bridge compilation failed: "+engine);
        if(!Arrays.equals(sha,digest(copy)))throw new IllegalStateException("Compiler modified isolated input jar");
        loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL(),copy.toUri().toURL()},parentApi.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("com.chaosthedude.explorerscompass.unified.search.ScanCoordinator"))synchronized(getClassLoadingLock(name)) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        };
    }
    Object create(String type) throws Exception {return loader.loadClass(type).getConstructor().newInstance();}
    private static byte[] digest(Path path) throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));}
    @Override public void close() throws Exception {
        loader.close();
        if(!Arrays.equals(sha,digest(input)))throw new IllegalStateException("External input changed during benchmark");
        try(var files=Files.walk(directory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
}
