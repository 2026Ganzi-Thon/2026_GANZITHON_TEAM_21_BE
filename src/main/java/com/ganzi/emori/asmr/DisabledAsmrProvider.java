package com.ganzi.emori.asmr;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Profile("!asmr-test")
public class DisabledAsmrProvider implements AsmrProvider {
    public boolean available(){return false;}
    public String name(){return "UNCONFIGURED";}
    public Result start(Request request){
        throw new IllegalStateException("ASMR provider is not configured");
    }
    public Result poll(String jobId){throw new IllegalStateException("ASMR provider is not configured");}
}
