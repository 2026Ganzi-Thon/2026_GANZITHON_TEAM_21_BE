package com.ganzi.emori.sound;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Profile("sound-import")
public class SoundImportRunner implements ApplicationRunner {
    private final SoundService sounds;
    private final String file;
    private final String title;
    private final String color;
    public SoundImportRunner(SoundService sounds,
            @Value("${emori.sound-import.file}") String file,
            @Value("${emori.sound-import.title}") String title,
            @Value("${emori.sound-import.color:}") String color) {
        this.sounds=sounds;this.file=file;this.title=title;this.color=color;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        var id=sounds.register(title,color.isBlank()?null:color,Files.readAllBytes(Path.of(file)));
        System.out.println("Registered sound ID: "+id);
    }
}
