package com.ganzi.emori.push;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/devices")
public class DeviceController {
    private final DeviceService devices;
    public DeviceController(DeviceService devices){this.devices=devices;}
    public record Registration(String platform,String deliveryToken){}
    @PutMapping("/{installationId}")
    public ResponseEntity<Void> register(HttpServletRequest request,@PathVariable String installationId,
                                         @RequestBody Registration input){
        devices.register((UUID)request.getAttribute("userId"),request.getHeader("Authorization"),installationId,input);
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{installationId}")
    public ResponseEntity<Void> delete(HttpServletRequest request,@PathVariable String installationId){
        devices.delete((UUID)request.getAttribute("userId"),installationId);
        return ResponseEntity.noContent().build();
    }
}
