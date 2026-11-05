package com.chaosthedude.explorerscompass.unified.client;

import com.david.compass.runtime.game.client.DimensionNames;

import com.chaosthedude.explorerscompass.unified.data.FailureReportText;
import com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.loading.FMLPaths;

/** 独立于可关闭的搜索历史；IO永不占用游戏线程，报告跨存档保留。 */
public final class FailureReports {
    public record Report(UUID id,String text,Path file) {}
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"Compass-failure-reports");t.setDaemon(true);return t;});
    private static final Map<UUID,Report> reports=new LinkedHashMap<>();
    private static final Set<UUID> seen=new LinkedHashSet<>();
    public static Path directory(){return FMLPaths.GAMEDIR.get().resolve("logs/explorerscompass-unified/error-reports");}
    static {Runtime.getRuntime().addShutdownHook(new Thread(()->{IO.shutdown();try{IO.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}},"Compass-reports-close"));}
    private FailureReports() {}
    public static void accept(Connection source,SearchLifecyclePacket packet) {
        var mc=Minecraft.getInstance();var data=packet.data();
        if(mc.getConnection()==null || mc.getConnection().getConnection()!=source || mc.player==null
            || !data.hasUUID("player") || !data.getUUID("player").equals(mc.player.getUUID())
            || !packet.terminal() || !data.contains("failureReport") || !seen.add(packet.token()))return;
        while(seen.size()>2048)seen.remove(seen.iterator().next());
        String name=targetName(data),dimension=DimensionNames.name(com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(data.getString("dimension")));
        String kind=data.getString("kind").equals("biome")?"群系":"结构";
        String text=format(packet.token(),data,name,dimension);
        String stamp=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS"));
        Path file=directory().resolve(stamp+"_"+packet.token()+".txt");
        Report report=new Report(packet.token(),text,file);remember(report);
        try {IO.execute(()->{
            String failure=null;
            try {
                Files.createDirectories(directory());Path temp=Files.createTempFile(directory(),"report-",".tmp");
                try {
                    Files.writeString(temp,text,StandardCharsets.UTF_8);
                    try {Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}
                    catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file);}
                }finally{Files.deleteIfExists(temp);}
            }catch(Exception error){failure=error.getClass().getSimpleName()+": "+FailureReportText.clean(error.getMessage(),180);}
            String savedError=failure;
            mc.execute(()->{if(mc.getConnection()!=null && mc.getConnection().getConnection()==source)notify(report,name,kind,dimension,savedError);});
        });}catch(RejectedExecutionException busy){notify(report,name,kind,dimension,"报告写入队列已满，仍可查看内存报告");}
    }
    private static void remember(Report report){reports.put(report.id(),report);while(reports.size()>128)reports.remove(reports.keySet().iterator().next());}
    private static String targetName(CompoundTag data) {
        var key=com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(data.getString("category"));
        return key==null?data.getString("category"):data.getString("kind").equals("biome")?FilterUi.biomeName(key):StructureUtils.getPrettyStructureName(key);
    }
    private static String format(UUID id,CompoundTag data,String name,String dimension) {
        var details=data.getCompound("failureReport");var out=new StringBuilder("探险者指南针：搜索失败报告\n");
        out.append("说明：搜索未找到不代表游戏崩溃；以下记录本次请求的实际终态。\n");
        out.append("报告/请求ID：").append(id).append("\n现实开始：").append(Instant.ofEpochMilli(data.getLong("startedAt")))
            .append("\n现实结束：").append(ZonedDateTime.now()).append("\n目标：").append(name).append(" ( ").append(data.getString("category")).append(" )")
            .append("\n维度：").append(dimension).append(" ( ").append(data.getString("dimension")).append(" )")
            .append("\n起点XYZ：").append(Arrays.toString(data.getIntArray("origin"))).append('\n');
        for(String key:List.of("requestKind","kind","targets","mode","result","samples","candidates","checks","chunkRequests"))out.append(key).append(": ").append(data.get(key)).append('\n');
        out.append("总耗时ms：").append(data.getLong("durationNanos")/1e6).append('\n');
        for(String key:List.of("mainNanos","workerNanos","preparationNanos","confirmationNanos","waitingNanos"))out.append(key).append(": ").append(data.getLong(key)).append('\n');
        for(String key:List.of("minecraft","forge","mod","radius","sampleLimit","generator","biomeSource","rules","diagnostics","exceptions","travelSummary"))
            if(details.contains(key))out.append(key).append(": ").append(details.contains(key,net.minecraft.nbt.Tag.TAG_STRING)?details.getString(key):details.get(key)).append('\n');
        return FailureReportText.bounded(out.toString());
    }
    private static void notify(Report report,String name,String kind,String dimension,String error) {
        var player=Minecraft.getInstance().player;if(player==null)return;
        player.displayClientMessage(Component.literal("糟糕，在搜索「"+name+"」"+kind+"（在「"+dimension+"」维度生成）时发生错误！"),false);
        player.displayClientMessage(Component.literal("点击查看错误报告").withStyle(style->style.withColor(ChatFormatting.RED).withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/david report "+report.id()))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(error==null?report.file().toString():"保存失败："+error)))),false);
        if(error!=null)player.displayClientMessage(Component.literal("[指南针报告] 保存失败："+error),false);
    }
    public static void open(UUID id) {
        var mc=Minecraft.getInstance();var parent=mc.screen;Report cached=reports.get(id);
        if(cached!=null){mc.setScreen(new FailureReportScreen(parent,cached));return;}
        try{IO.execute(()->{
            Report loaded=null;
            try(var files=Files.list(directory())) {
                Path file=files.filter(p->p.getFileName().toString().endsWith("_"+id+".txt")).findFirst().orElse(null);
                if(file!=null && Files.size(file)<=FailureReportText.MAX_BYTES)loaded=new Report(id,Files.readString(file,StandardCharsets.UTF_8),file);
            }catch(Exception ignored){}
            Report result=loaded;mc.execute(()->{if(result!=null){remember(result);mc.setScreen(new FailureReportScreen(parent,result));}
                else SearchLogClient.message("没有找到该错误报告："+id);});
        });}catch(RejectedExecutionException busy){SearchLogClient.message("报告读取队列繁忙，请稍后重试");}
    }
    public static void reveal(Report report) {
        try{IO.execute(()->{
            try {
                if(Files.isRegularFile(report.file()) && System.getProperty("os.name","").startsWith("Windows"))
                    new ProcessBuilder("explorer.exe","/select,"+report.file().toAbsolutePath()).start();
                else net.minecraft.Util.getPlatform().openFile(directory().toFile());
            }catch(Exception error){SearchLogClient.message("无法打开报告文件夹："+error.getClass().getSimpleName());}
        });}catch(RejectedExecutionException busy){SearchLogClient.message("报告队列繁忙，请稍后重试");}
    }
}
