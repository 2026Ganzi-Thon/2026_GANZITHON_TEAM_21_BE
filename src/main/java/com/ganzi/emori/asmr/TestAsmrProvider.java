package com.ganzi.emori.asmr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Explicit test profile and switch only. Synthetic silence is never described as AI output.
@Component
@Profile("asmr-test & !postgres")
@ConditionalOnProperty(name="EMORI_ASMR_TEST_GENERATOR",havingValue="true")
public class TestAsmrProvider implements AsmrProvider {
    private final AtomicBoolean pauseOnce=new AtomicBoolean();
    private volatile CountDownLatch entered=new CountDownLatch(0),resume=new CountDownLatch(0);
    public void pauseNextStart(){entered=new CountDownLatch(1);resume=new CountDownLatch(1);pauseOnce.set(true);}
    public boolean awaitPaused() throws InterruptedException{return entered.await(10,TimeUnit.SECONDS);}
    public void resume(){resume.countDown();}
    public boolean available(){return true;}
    public String name(){return "TEST_FIXTURE";}
    public Result start(Request request){
        if(pauseOnce.compareAndSet(true,false)){
            entered.countDown();
            try {if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture pause timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
        if(request.maxDurationMs()!=30000 || request.vocalsAllowed() || request.lyricsAllowed())
            throw new IllegalArgumentException("Invalid ASMR policy");
        String key=request.requestKey(),description=request.description();int position=request.position();
        if(description!=null && description.contains("TEST_FAIL_ONE") && position==2 && key.endsWith(":1"))
            return new Result("FAILED",null,null,null,null,"GENERATION_FAILED");
        int duration=description!=null && description.contains("TEST_OVER_LIMIT") && position==2 ? 30001 : 30000;
        return new Result("READY",null,"Test fixture " + position,duration,silentWav(duration),null);
    }
    public Result poll(String jobId){throw new IllegalStateException("Test fixture has no async jobs");}
    private byte[] silentWav(int durationMs){
        int samples=(int)(8000L*durationMs/1000), dataBytes=samples*2;
        ByteBuffer out=ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+dataBytes);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        out.putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        return out.array();
    }
}
