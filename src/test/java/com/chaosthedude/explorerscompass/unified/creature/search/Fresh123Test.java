package com.chaosthedude.explorerscompass.unified.creature.search;

import com.david.compass.runtime.game.search.ScanCoordinator;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import com.david.compass.runtime.game.search.VerificationSlots;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureCatalog;
import org.junit.jupiter.api.Test;

class Fresh123Test {
    @Test void habitatMayFinishAtFirstConfirmedWithoutChangingOrdinaryProof()throws Exception{
        var lane=new com.david.compass.runtime.game.search.ScanCoordinator.Lane<Integer>(){
            public boolean available(long limit){return true;}public List<Integer> sample(){return List.of(42);}public boolean backgroundAllowed(){return false;}
        };
        var scan=new com.david.compass.runtime.game.search.ScanCoordinator<>(List.of(lane),v->v,v->true,100,false).withFirstConfirmed();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();try{scan.step(pool);assertFalse(scan.done());scan.step(pool);assertTrue(scan.done());assertEquals(42,scan.result());}finally{pool.shutdownNow();}
    }
    @Test void fourRoundsUse256DistinctChunksWithOneAnchor(){
        var origin=new BlockPos(-17,70,-33);Set<Long> checked=new HashSet<>();
        for(int i=0;i<4;i++){var batch=CreatureSearch.chunkPlan(origin,64,checked,origin,80000);assertEquals(64,batch.size());for(var chunk:batch)assertTrue(checked.add(chunk.toLong()));}
        assertEquals(256,checked.size());
    }
    @Test void preExplorePlanExactlyCoversBoundedCircleAtNegativeOrigin(){
        var origin=new BlockPos(-17,70,-33);int radius=512;var plan=CreaturePreExplore.plan(origin,radius,-200,300,-400,200);
        Set<Long> expected=new HashSet<>();
        for(int x=-40;x<40;x++)for(int z=-40;z<40;z++){long bx=x*16L+8,bz=z*16L+8,dx=bx-origin.getX(),dz=bz-origin.getZ();if(dx*dx+dz*dz<=512L*512 && bx>=-200 && bx<300 && bz>=-400 && bz<200)expected.add(net.minecraft.world.level.ChunkPos.asLong(x,z));}
        assertEquals(expected,new HashSet<>(plan.stream().map(c->c.toLong()).toList()));assertEquals(expected.size(),plan.size());
        long last=-1;for(var chunk:plan){long dx=chunk.getMiddleBlockX()-origin.getX(),dz=chunk.getMiddleBlockZ()-origin.getZ(),d=dx*dx+dz*dz;assertTrue(d>=last);last=d;}
    }
    @Test void backgroundSlotCannotJumpForegroundQueue(){
        Object[] users={new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
        try{for(int i=0;i<4;i++)assertTrue(VerificationSlots.acquire(users[i]));assertFalse(VerificationSlots.acquire(users[4]));VerificationSlots.release(users[0]);assertFalse(VerificationSlots.acquireBackground(users[5]));assertTrue(VerificationSlots.acquire(users[4]));VerificationSlots.release(users[1]);assertTrue(VerificationSlots.acquireBackground(users[5]));}
        finally{for(var user:users)VerificationSlots.release(user);}
    }
    @Test void standardAndPackedCatalogHaveIdenticalText()throws Exception{
        String source="[{\"entity\":\"minecraft:turtle\",\"name\":\"海龟\"}]";byte[] raw=source.getBytes(java.nio.charset.StandardCharsets.UTF_8);var out=new ByteArrayOutputStream();
        try(var compressed=new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(out)){compressed.write(raw);}
        var reader=CreatureCatalog.class.getDeclaredMethod("reader",InputStream.class);reader.setAccessible(true);
        for(byte[] bytes:List.of(raw,out.toByteArray()))try(var decoded=(Reader)reader.invoke(null,new ByteArrayInputStream(bytes))){var text=new StringWriter();decoded.transferTo(text);assertEquals(source,text.toString());}
    }
}
