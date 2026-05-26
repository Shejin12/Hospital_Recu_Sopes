package hospital.controlador;

import hospital.gestor.GestorRecursos;
import hospital.modelo.NivelTriaje;
import hospital.modelo.Paciente;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador REST para registrar pacientes manualmente desde el Frontend.
 * Expone el endpoint POST /api/pacientes que permite crear un paciente
 * de cualquier nivel de triaje y lanzarlo como hilo en la simulación.
 */
@RestController
@RequestMapping("/api/pacientes")
@CrossOrigin(origins = "*") // Permitir peticiones CORS desde cualquier origen
public class PacienteController {

    private final GestorRecursos gestorRecursos;

    @Autowired
    public PacienteController(GestorRecursos gestorRecursos) {
        this.gestorRecursos = gestorRecursos;
    }

    /**
     * Crea un nuevo paciente con el nivel de triaje especificado y lo lanza
     * como un hilo independiente en la simulación.
     *
     * @param payload JSON con la llave "nivel", ej: {"nivel": "NIVEL_1_CRITICO"}
     * @return Respuesta HTTP 200 con el ID del paciente creado, o 400 si el nivel es inválido.
     */
    @PostMapping
    public ResponseEntity<String> agregarPaciente(@RequestBody Map<String, String> payload) {
        String nivelStr = payload.get("nivel");

        if (nivelStr == null || nivelStr.isEmpty()) {
            return ResponseEntity.badRequest().body("Falta el campo 'nivel' en el cuerpo de la petición.");
        }

        NivelTriaje nivelTriaje;
        try {
            nivelTriaje = NivelTriaje.valueOf(nivelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body("Nivel de triaje inválido: '" + nivelStr + "'. " +
                      "Valores válidos: NIVEL_1_CRITICO, NIVEL_2_EMERGENCIA, NIVEL_3_URGENTE, " +
                      "NIVEL_4_MENOS_URGENTE, NIVEL_5_NO_URGENTE");
        }

        // Crear el paciente con el nivel de triaje especificado
        Paciente nuevoPaciente = new Paciente(gestorRecursos, nivelTriaje);

        // Lanzar el paciente en un hilo nativo para no bloquear la respuesta HTTP
        Thread hiloPaciente = new Thread(nuevoPaciente, "Hilo-Paciente-" + nuevoPaciente.getId());
        hiloPaciente.start();

        System.out.println("[API REST] Paciente manual " + nuevoPaciente.getId() +
                           " creado con triaje: " + nivelTriaje);

        return ResponseEntity.ok("Paciente " + nuevoPaciente.getId() + " creado con nivel: " + nivelTriaje);
    }
}
