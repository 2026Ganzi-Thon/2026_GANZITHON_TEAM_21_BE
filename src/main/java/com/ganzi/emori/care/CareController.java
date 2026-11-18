package com.ganzi.emori.care;

import com.ganzi.emori.care.CareModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/care")
public class CareController {
    private final CareService care;
    public CareController(CareService care) { this.care=care; }
    @GetMapping public Care get(HttpServletRequest request) {return care.get((UUID)request.getAttribute("userId"));}
    @GetMapping("/resources/{id}") public CareResource resource(@PathVariable String id) {return care.resource(id);}
    @PutMapping("/notices/{id}/acknowledgement")
    public ResponseEntity<Void> acknowledge(HttpServletRequest request,@PathVariable String id,@RequestBody Acknowledgement input) {
        care.acknowledge((UUID)request.getAttribute("userId"),id,input);
        return ResponseEntity.noContent().build();
    }
}
