package hospital.gestor;

import hospital.modelo.NivelTriaje;
import hospital.modelo.Paciente;
import hospital.servicio.WebSocketPublisher;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.Map;

/**
 * Gestor central que administra los recursos del hospital de forma segura.
 * Convertido a @Service de Spring para inyectar dependencias y manejar Singleton.
 */
@Service
public class GestorRecursos {

    private final WebSocketPublisher publisher;

    // Recursos contables protegidos mediante Semáforos
    private final Semaphore salas = new Semaphore(10, true);
    private final Semaphore quirofanos = new Semaphore(3, true);
    private final Semaphore medicos = new Semaphore(8, true);
    private final Semaphore cirujanos = new Semaphore(4, true);
    private final Semaphore enfermeras = new Semaphore(10, true);
    private final Semaphore ventiladores = new Semaphore(5, true);
    private final Semaphore monitores = new Semaphore(8, true);

    // Cola de prioridad concurrente
    private final PriorityBlockingQueue<Paciente> salaDeEspera = new PriorityBlockingQueue<>();

    // Lock para asegurar exclusión mutua
    private final ReentrantLock lockAsignacion = new ReentrantLock(true);

    // Variables para Deadlock Controlado
    private Paciente pacienteDeadlockA = null;
    private Paciente pacienteDeadlockB = null;
    private CountDownLatch latchResolucionDeadlock = new CountDownLatch(1);
    private int idGanadorDeadlock = -1;

    @Autowired
    public GestorRecursos(WebSocketPublisher publisher) {
        this.publisher = publisher;
    }

    public void solicitarRecursos(Paciente paciente) {
        System.out.println("[GESTOR] Paciente " + paciente.getId() + " ingresa a la sala de espera.");
        salaDeEspera.put(paciente);
        
        // Emitir evento WS
        Map<String, Object> data = new HashMap<>();
        data.put("idPaciente", paciente.getId());
        data.put("triaje", paciente.getNivel().name());
        data.put("prioridad", paciente.getNivel().getPrioridad());
        publisher.enviarEvento("NUEVO_PACIENTE", "El paciente " + paciente.getId() + " ingresó a espera.", data);

        procesarCola();
    }

    public void forzarDeadlock(Paciente paciente) {
        System.out.println("[DEADLOCK-DEMO] Paciente " + paciente.getId() + " entra a modo interbloqueo.");
        
        boolean esPacienteA = false;
        
        synchronized (this) {
            if (pacienteDeadlockA == null) {
                pacienteDeadlockA = paciente;
                esPacienteA = true;
            } else if (pacienteDeadlockB == null) {
                pacienteDeadlockB = paciente;
            } else {
                solicitarRecursos(paciente);
                return;
            }
        }

        try {
            if (esPacienteA) {
                cirujanos.acquire(1);
                Thread.sleep(1000);
                boolean obtuvoVentilador = ventiladores.tryAcquire(1, 3, TimeUnit.SECONDS);
                
                if (!obtuvoVentilador) {
                    reportarDeadlock(paciente.getId(), "Cirujano", "Ventilador");
                    latchResolucionDeadlock.await();
                    aplicarResolucionDeadlock(paciente, true);
                } else {
                    paciente.notificarRecursosAsignados();
                }

            } else {
                ventiladores.acquire(1);
                Thread.sleep(1000);
                boolean obtuvoCirujano = cirujanos.tryAcquire(1, 3, TimeUnit.SECONDS);
                
                if (!obtuvoCirujano) {
                    reportarDeadlock(paciente.getId(), "Ventilador", "Cirujano");
                    latchResolucionDeadlock.await();
                    aplicarResolucionDeadlock(paciente, false);
                } else {
                    paciente.notificarRecursosAsignados();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportarDeadlock(int idPaciente, String recursoRetenido, String recursoFaltante) {
        System.err.println(">>>> [ALERTA] Paciente " + idPaciente + " detectó DEADLOCK.");
        Map<String, Object> data = new HashMap<>();
        data.put("idPaciente", idPaciente);
        data.put("recursoRetenido", recursoRetenido);
        data.put("recursoFaltante", recursoFaltante);
        
        // Ojo: Esto se emitirá dos veces (una por paciente)
        publisher.enviarEvento("DEADLOCK_DETECTADO", 
            "El paciente " + idPaciente + " retiene " + recursoRetenido + " pero necesita " + recursoFaltante, data);
    }

    private void aplicarResolucionDeadlock(Paciente paciente, boolean esPacienteA) throws InterruptedException {
        if (paciente.getId() == idGanadorDeadlock) {
            System.out.println("[RESOLUCIÓN] Paciente " + paciente.getId() + " GANÓ.");
            publisher.enviarEvento("DEADLOCK_RESUELTO", "Paciente " + paciente.getId() + " fue elegido ganador.", null);
            
            Thread.sleep(500); 
            if (esPacienteA) ventiladores.acquire(1);
            else cirujanos.acquire(1);
            
            salas.acquire(1);
            quirofanos.acquire(1);
            enfermeras.acquire(2);
            monitores.acquire(1);
            
            paciente.notificarRecursosAsignados();
            publisher.enviarEvento("ATENCION_INICIADA", "Paciente " + paciente.getId() + " inició atención (Deadlock superado).", paciente.getId());
        } else {
            System.out.println("[RESOLUCIÓN] Paciente " + paciente.getId() + " PERDIÓ.");
            if (esPacienteA) cirujanos.release(1);
            else ventiladores.release(1);
            
            solicitarRecursos(paciente); 
        }
    }

    public void resolverDeadlock(String idPacienteElegido) {
        try {
            this.idGanadorDeadlock = Integer.parseInt(idPacienteElegido);
            latchResolucionDeadlock.countDown();
        } catch (NumberFormatException e) {
            System.err.println("ID inválido.");
        }
    }

    public void liberarRecursos(Paciente paciente) {
        NivelTriaje nivel = paciente.getNivel();
        
        System.out.println("[LIBERANDO] " + paciente.toString() + " devuelve sus recursos.");
        publisher.enviarEvento("RECURSOS_LIBERADOS", "Paciente " + paciente.getId() + " liberó sus recursos.", paciente.getId());

        if (nivel.getSalasRequeridas() > 0) salas.release(nivel.getSalasRequeridas());
        if (nivel.getQuirofanosRequeridos() > 0) quirofanos.release(nivel.getQuirofanosRequeridos());
        if (nivel.getMedicosRequeridos() > 0) medicos.release(nivel.getMedicosRequeridos());
        if (nivel.getCirujanosRequeridos() > 0) cirujanos.release(nivel.getCirujanosRequeridos());
        if (nivel.getEnfermerasRequeridas() > 0) enfermeras.release(nivel.getEnfermerasRequeridas());
        if (nivel.getVentiladoresRequeridos() > 0) ventiladores.release(nivel.getVentiladoresRequeridos());
        if (nivel.getMonitoresRequeridos() > 0) monitores.release(nivel.getMonitoresRequeridos());

        procesarCola();
    }

    private void procesarCola() {
        lockAsignacion.lock();
        try {
            while (!salaDeEspera.isEmpty()) {
                Paciente pacienteEnEspera = salaDeEspera.peek();
                NivelTriaje nivel = pacienteEnEspera.getNivel();

                if (hayRecursosDisponibles(nivel)) {
                    try {
                        if (nivel.getSalasRequeridas() > 0) salas.acquire(nivel.getSalasRequeridas());
                        if (nivel.getQuirofanosRequeridos() > 0) quirofanos.acquire(nivel.getQuirofanosRequeridos());
                        if (nivel.getMedicosRequeridos() > 0) medicos.acquire(nivel.getMedicosRequeridos());
                        if (nivel.getCirujanosRequeridos() > 0) cirujanos.acquire(nivel.getCirujanosRequeridos());
                        if (nivel.getEnfermerasRequeridas() > 0) enfermeras.acquire(nivel.getEnfermerasRequeridas());
                        if (nivel.getVentiladoresRequeridos() > 0) ventiladores.acquire(nivel.getVentiladoresRequeridos());
                        if (nivel.getMonitoresRequeridos() > 0) monitores.acquire(nivel.getMonitoresRequeridos());
                        
                        salaDeEspera.poll();
                        System.out.println("[ASIGNADO] Recursos entregados a " + pacienteEnEspera.toString());
                        
                        Map<String, Object> data = new HashMap<>();
                        data.put("idPaciente", pacienteEnEspera.getId());
                        data.put("triaje", nivel.name());
                        data.put("prioridad", nivel.getPrioridad());
                        data.put("tiempoAtencion", pacienteEnEspera.getTiempoAtencionMs());
                        publisher.enviarEvento("RECURSOS_ASIGNADOS", "Paciente " + pacienteEnEspera.getId() + " inició atención.", data);

                        pacienteEnEspera.notificarRecursosAsignados();
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    break; 
                }
            }
        } finally {
            lockAsignacion.unlock();
        }
    }

    private boolean hayRecursosDisponibles(NivelTriaje nivel) {
        return salas.availablePermits() >= nivel.getSalasRequeridas() &&
               quirofanos.availablePermits() >= nivel.getQuirofanosRequeridos() &&
               medicos.availablePermits() >= nivel.getMedicosRequeridos() &&
               cirujanos.availablePermits() >= nivel.getCirujanosRequeridos() &&
               enfermeras.availablePermits() >= nivel.getEnfermerasRequeridas() &&
               ventiladores.availablePermits() >= nivel.getVentiladoresRequeridos() &&
               monitores.availablePermits() >= nivel.getMonitoresRequeridos();
    }
}
