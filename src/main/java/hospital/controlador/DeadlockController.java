package hospital.controlador;

import hospital.gestor.GestorRecursos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador REST para manejar la resolución manual de un Deadlock desde el Frontend.
 */
@RestController
@RequestMapping("/api/deadlock")
@CrossOrigin(origins = "*") // Permitir peticiones desde cualquier frontend
public class DeadlockController {

    private final GestorRecursos gestorRecursos;

    @Autowired
    public DeadlockController(GestorRecursos gestorRecursos) {
        this.gestorRecursos = gestorRecursos;
    }

    /**
     * Endpoint para destrabar el sistema eligiendo a un paciente ganador.
     * @param payload JSON con la llave "idPacienteElegido".
     * @return Respuesta HTTP
     */
    @PostMapping("/resolver")
    public ResponseEntity<String> resolverDeadlock(@RequestBody Map<String, String> payload) {
        String idPacienteElegido = payload.get("idPacienteElegido");
        if (idPacienteElegido == null || idPacienteElegido.isEmpty()) {
            return ResponseEntity.badRequest().body("Falta el idPacienteElegido");
        }

        System.out.println("[API REST] Petición recibida para resolver deadlock a favor de: " + idPacienteElegido);
        gestorRecursos.resolverDeadlock(idPacienteElegido);
        
        return ResponseEntity.ok("Resolución enviada al gestor.");
    }
}
