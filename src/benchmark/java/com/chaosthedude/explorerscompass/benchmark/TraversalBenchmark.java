package com.chaosthedude.explorerscompass.benchmark;

import com.chaosthedude.explorerscompass.unified.search.RectFrontier;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** 旧算法只从用户提供的原始 jar 加载；动态桥接仅调用公开 API，没有算法副本。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class TraversalBenchmark {
    public interface Walker { long scan(int radius); }
    @Param({"unified105", "unified110", "enhance160"}) public String engine;
    @Param({"64", "1250"}) public int radius;
    private Walker walker;
    private ExternalApiBridge bridge;
    @Setup(Level.Trial)
    public void prepare() throws Exception {
        if (engine.equals("unified110")) {
            walker = limit -> {
                long squared = (long)limit * limit, checksum = 0;
                var geometry = new RectFrontier.Geometry(0, 0, 1, 0, 0, 0, 0);
                var frontier = new RectFrontier(geometry, geometry.domain(limit), 16, squared);
                while (frontier.available(squared)) {
                    long point = frontier.next(); int x = (int)(point >> 32), z = (int)point;
                    if (frontier.inRange(x, z)) checksum += 1 + (long)x * x + (long)z * z;
                }
                return checksum;
            };
            checkWorkload(); return;
        }
        String type = engine.equals("unified105")
                ? "com.chaosthedude.explorerscompass.unified.search.RadialWalk"
                : "com.chaosthedude.explorerscompass.worker.DiscWalker";
        String x = engine.equals("unified105") ? "x" : "getX";
        String z = engine.equals("unified105") ? "z" : "getZ";
        String band = engine.equals("unified105") ? "band" : "getCoveredLength";
        // 直接 Java 调用便于 JIT 内联，避免每采样点三次反射所造成的比较偏差。
        String source = "public final class ExternalWalker implements " + Walker.class.getCanonicalName()
                + " { public long scan(int radius) { " + type + " w = new " + type + "(1,0); long result=0, maximum=(long)radius*radius;"
                + " while(w." + band + "()<=radius) { int x=w." + x + "(), z=w." + z + "(); long d=(long)x*x+(long)z*z;"
                + " if(d<=maximum) result+=1+d; w.advance(); } return result; }}";
        bridge = new ExternalApiBridge(engine,"ExternalWalker",source,Walker.class);
        walker = (Walker)bridge.create("ExternalWalker"); checkWorkload();
    }
    private void checkWorkload() {
        long expected=0;
        for(int x=-64;x<=64;x++)for(int z=-64;z<=64;z++)if((long)x*x+(long)z*z<=4096)expected+=1+(long)x*x+(long)z*z;
        if(walker.scan(64)!=expected)throw new IllegalStateException("External traversal coverage mismatch: "+engine);
    }
    @Benchmark public long traversal() { return walker.scan(radius); }
    @TearDown(Level.Trial) public void close() throws Exception {
        if (bridge != null) bridge.close();
    }
}
