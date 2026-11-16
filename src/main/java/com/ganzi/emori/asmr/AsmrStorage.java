package com.ganzi.emori.asmr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class AsmrStorage {
    private final Path root;
    public AsmrStorage(@Value("${EMORI_ASMR_STORAGE_ROOT:./data/asmr}") String root){
        this.root=Path.of(root).toAbsolutePath().normalize();
    }
    public String key(UUID generation,UUID track,int attempt){
        return generation+"/"+track+"/"+attempt+".wav";
    }
    public void write(String key,byte[] bytes) throws IOException {
        Path target=path(key);
        Files.createDirectories(target.getParent());
        Path temporary=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());
        try {
            Files.write(temporary,bytes);
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(java.nio.file.AtomicMoveNotSupportedException e){
                Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    public byte[] read(String key) throws IOException {return Files.readAllBytes(path(key));}
    private Path path(String key){
        Path result=root.resolve(key).normalize();
        if(!result.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return result;
    }
}
