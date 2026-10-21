package com.chaosthedude.explorerscompass.unified.data;

import java.nio.charset.StandardCharsets;

/** 报告与网络异常摘要共用上限，不把整个服务器日志发送给客户端。 */
public final class FailureReportText {
    public static final int MAX_BYTES=64*1024;
    private FailureReportText() {}
    public static boolean reportable(String reason) {
        return java.util.Set.of("range_exhausted","sample_limit","error","travel_failed","timeout").contains(reason);
    }
    public static String exception(Throwable error) {
        StringBuilder out=new StringBuilder();
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        int frames=0;
        for(int cause=0;error!=null && cause<3 && seen.add(error);cause++,error=error.getCause()) {
            out.append(error.getClass().getName()).append(": ").append(clean(error.getMessage(),1000)).append('\n');
            for(var frame:error.getStackTrace()) {
                if(frames++>=12)break;
                out.append("  at ").append(frame).append('\n');
            }
        }
        return out.toString();
    }
    public static String clean(String text,int maximum) {
        if(text==null)return "";
        String safe=text.replaceAll("(?i)(accessToken|authorization|password|token)=?\\s*[^\\s,;]+","$1=[redacted]");
        return safe.length()>maximum?safe.substring(0,maximum)+"…":safe;
    }
    public static String bounded(String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<=MAX_BYTES)return text;
        int end=MAX_BYTES-64;
        while(end>0 && (bytes[end]&0xc0)==0x80)end--;
        return new String(bytes,0,end,StandardCharsets.UTF_8)+"\n[报告达到64 KiB，后续内容已截断]\n";
    }
}
