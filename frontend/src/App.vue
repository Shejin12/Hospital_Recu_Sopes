<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { Client } from '@stomp/stompjs';

// ─── Definición de los 5 niveles de triaje para los botones manuales ──────────
const nivelesTriaje = [
  {
    nivel: 'NIVEL_1_CRITICO',
    label: 'Nivel 1 — Crítico',
    desc: 'Quirófano + Cirujano + 2 Enf + Vent + Monitor',
    btnClass: 'btn-danger',
    badgeClass: 'bg-danger text-white',
  },
  {
    nivel: 'NIVEL_2_EMERGENCIA',
    label: 'Nivel 2 — Emergencia',
    desc: 'Sala + Médico + Enfermera + Monitor',
    btnClass: 'btn-warning text-dark',
    badgeClass: 'bg-warning text-dark',
  },
  {
    nivel: 'NIVEL_3_URGENTE',
    label: 'Nivel 3 — Urgente',
    desc: 'Sala + Médico',
    btnClass: 'btn-warning text-dark',
    badgeClass: 'bg-warning text-dark',
  },
  {
    nivel: 'NIVEL_4_MENOS_URGENTE',
    label: 'Nivel 4 — Menos Urgente',
    desc: 'Sala + Médico',
    btnClass: 'btn-success',
    badgeClass: 'bg-success text-white',
  },
  {
    nivel: 'NIVEL_5_NO_URGENTE',
    label: 'Nivel 5 — No Urgente',
    desc: 'Sala + Médico',
    btnClass: 'btn-primary',
    badgeClass: 'bg-primary text-white',
  },
];

/**
 * Llama al endpoint REST del backend para crear un paciente manual
 * con el nivel de triaje seleccionado.
 */
const agregarPacienteManual = async (nivel) => {
  try {
    const res = await fetch('http://localhost:8081/api/pacientes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nivel }),
    });
    if (!res.ok) {
      const msg = await res.text();
      console.error('Error al agregar paciente:', msg);
    }
  } catch (err) {
    console.error('Error de red al agregar paciente:', err);
  }
};

// Estado de Recursos
const recursos = ref({
  Salas: { total: 10, usados: 0 },
  Quirofanos: { total: 3, usados: 0 },
  Medicos: { total: 8, usados: 0 },
  Cirujanos: { total: 4, usados: 0 },
  Enfermeras: { total: 10, usados: 0 },
  Ventiladores: { total: 5, usados: 0 },
  Monitores: { total: 8, usados: 0 }
});

const requisitosTriaje = {
  NIVEL_1_CRITICO: { Salas: 0, Quirofanos: 1, Medicos: 0, Cirujanos: 1, Enfermeras: 2, Ventiladores: 1, Monitores: 1 },
  NIVEL_2_EMERGENCIA: { Salas: 1, Quirofanos: 0, Medicos: 1, Cirujanos: 0, Enfermeras: 1, Ventiladores: 0, Monitores: 1 },
  NIVEL_3_URGENTE: { Salas: 1, Quirofanos: 0, Medicos: 1, Cirujanos: 0, Enfermeras: 0, Ventiladores: 0, Monitores: 0 },
  NIVEL_4_MENOS_URGENTE: { Salas: 1, Quirofanos: 0, Medicos: 1, Cirujanos: 0, Enfermeras: 0, Ventiladores: 0, Monitores: 0 },
  NIVEL_5_NO_URGENTE: { Salas: 1, Quirofanos: 0, Medicos: 1, Cirujanos: 0, Enfermeras: 0, Ventiladores: 0, Monitores: 0 },
};

const pacientesEnEspera = ref([]);
const pacientesAtendidos = ref([]);
const logs = ref([]);
const stats = ref({ totalAtendidos: 0 });

const deadlock = ref({
  activo: false,
  involucrados: [],
  mensaje: '',
  detalles: [] // Lista de {idPaciente, recursoRetenido, recursoFaltante}
});

/**
 * Llama al endpoint que fuerza el escenario de deadlock controlado.
 * Crea dos pacientes Nivel 1 en modo interbloqueo.
 */
const simularDeadlock = async () => {
  try {
    const res = await fetch('http://localhost:8081/api/deadlock/simular', {
      method: 'POST',
    });
    if (!res.ok) {
      const msg = await res.text();
      console.error('Error al simular deadlock:', msg);
    }
  } catch (err) {
    console.error('Error de red al simular deadlock:', err);
  }
};

let animationFrameId;

// Colores por prioridad (Bootstrap)
const getTriajeColor = (prioridad) => {
  switch(prioridad) {
    case 1: return 'bg-danger text-white';
    case 2: return 'bg-warning text-dark';
    case 3: return 'bg-warning text-dark';
    case 4: return 'bg-success text-white';
    case 5: return 'bg-primary text-white';
    default: return 'bg-light text-dark';
  }
};

// Texto de prioridad (Bootstrap text colors)
const getTriajeTextColor = (prioridad) => {
  switch(prioridad) {
    case 1: return 'text-danger';
    case 2: return 'text-warning';
    case 3: return 'text-warning';
    case 4: return 'text-success';
    case 5: return 'text-primary';
    default: return 'text-muted';
  }
};

const getNivelBadge = (prioridad) => {
  switch(prioridad) {
    case 1: return 'bg-danger text-white';
    case 2: return 'bg-warning text-dark';
    case 3: return 'bg-warning text-dark';
    case 4: return 'bg-success text-white';
    case 5: return 'bg-primary text-white';
    default: return 'bg-secondary text-white';
  }
};

const getNivelProgressColor = (prioridad) => {
  switch(prioridad) {
    case 1: return 'bg-danger';
    case 2: return 'bg-warning';
    case 3: return 'bg-warning';
    case 4: return 'bg-success';
    case 5: return 'bg-primary';
    default: return 'bg-secondary';
  }
};

// La actualización de recursos ahora se maneja centralizadamente con el evento RECURSOS_ESTADO

const procesarEvento = (evento) => {
  const time = new Date(evento.timestamp).toLocaleTimeString();
  logs.value.unshift(`[${time}] ${evento.mensaje}`);
  if (logs.value.length > 50) logs.value.pop();

  if (evento.tipo === 'NUEVO_PACIENTE') {
    if (pacientesAtendidos.value.some(p => p.idPaciente === evento.datos.idPaciente)) return;
    pacientesEnEspera.value.push({
      idPaciente: evento.datos.idPaciente,
      triaje: evento.datos.triaje,
      prioridad: evento.datos.prioridad
    });
    pacientesEnEspera.value.sort((a, b) => a.prioridad - b.prioridad || a.idPaciente - b.idPaciente);
  }
  else if (evento.tipo === 'RECURSOS_ASIGNADOS') {
    const idx = pacientesEnEspera.value.findIndex(p => p.idPaciente === evento.datos.idPaciente);
    if (idx !== -1) pacientesEnEspera.value.splice(idx, 1);
    pacientesAtendidos.value.push({
      idPaciente: evento.datos.idPaciente,
      triaje: evento.datos.triaje,
      prioridad: evento.datos.prioridad,
      tiempoTotal: evento.datos.tiempoAtencion,
      startTime: Date.now(),
      progreso: 0
    });
  }
  else if (evento.tipo === 'RECURSOS_LIBERADOS') {
    const id = evento.datos;
    const idx = pacientesAtendidos.value.findIndex(p => p.idPaciente === id);
    if (idx !== -1) {
      pacientesAtendidos.value.splice(idx, 1);
      stats.value.totalAtendidos++;
    }
  }
  else if (evento.tipo === 'RECURSOS_ESTADO') {
    const state = evento.datos;
    recursos.value.Salas.usados = recursos.value.Salas.total - state.salas;
    recursos.value.Quirofanos.usados = recursos.value.Quirofanos.total - state.quirofanos;
    recursos.value.Medicos.usados = recursos.value.Medicos.total - state.medicos;
    recursos.value.Cirujanos.usados = recursos.value.Cirujanos.total - state.cirujanos;
    recursos.value.Enfermeras.usados = recursos.value.Enfermeras.total - state.enfermeras;
    recursos.value.Ventiladores.usados = recursos.value.Ventiladores.total - state.ventiladores;
    recursos.value.Monitores.usados = recursos.value.Monitores.total - state.monitores;
  }
  else if (evento.tipo === 'DEADLOCK_DETECTADO') {
    deadlock.value.activo = true;
    deadlock.value.mensaje = 'INTERBLOQUEO DETECTADO';
    const id = evento.datos.idPaciente;
    if (!deadlock.value.involucrados.includes(id)) {
      deadlock.value.involucrados.push(id);
    }
    // Guardar detalles de recursos para mostrar en el modal
    const yaDetallado = deadlock.value.detalles.find(d => d.idPaciente === id);
    if (!yaDetallado) {
      deadlock.value.detalles.push({
        idPaciente: id,
        recursoRetenido: evento.datos.recursoRetenido,
        recursoFaltante: evento.datos.recursoFaltante,
      });
    }
  }
  else if (evento.tipo === 'DEADLOCK_RESUELTO') {
    deadlock.value.activo = false;
    deadlock.value.involucrados = [];
    deadlock.value.detalles = [];
  }
};

const updateProgress = () => {
  const now = Date.now();
  pacientesAtendidos.value.forEach(p => {
    const elapsed = now - p.startTime;
    p.progreso = Math.min(100, (elapsed / p.tiempoTotal) * 100);
  });
  animationFrameId = requestAnimationFrame(updateProgress);
};

const resolverDeadlock = async (idPaciente) => {
  try {
    await fetch('http://localhost:8081/api/deadlock/resolver', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idPacienteElegido: idPaciente.toString() })
    });
  } catch (err) {
    console.error('Error al resolver deadlock:', err);
  }
};

let stompClient;

onMounted(() => {
  stompClient = new Client({
    brokerURL: 'ws://localhost:8081/ws-hospital',
    reconnectDelay: 5000,
    onConnect: () => {
      console.log('Conectado al WS');
      stompClient.subscribe('/topic/eventos', (mensaje) => {
        procesarEvento(JSON.parse(mensaje.body));
      });
    }
  });
  stompClient.activate();
  animationFrameId = requestAnimationFrame(updateProgress);
});

onUnmounted(() => {
  if (stompClient) stompClient.deactivate();
  cancelAnimationFrame(animationFrameId);
});
</script>

<template>
  <div class="container-fluid py-4 bg-light min-vh-100">

    <!-- ── Header ─────────────────────────────────────────────────────── -->
    <header class="d-flex justify-content-between align-items-center mb-3 border-bottom pb-2">
      <div>
        <h1 class="h3 text-success fw-bold mb-0">Hospital Central — Emergencias</h1>
        <small class="text-muted">Sistema de simulación concurrente en tiempo real</small>
      </div>
      <div class="bg-white px-3 py-2 rounded shadow border border-success text-center">
        <div class="text-muted text-uppercase small fw-semibold">Total Atendidos</div>
        <div class="h3 fw-bold text-success mb-0">{{ stats.totalAtendidos }}</div>
      </div>
    </header>

    <!-- ── Panel de Control Manual ───────────────────────────────────── -->
    <div class="card mb-3 border-secondary">
      <div class="card-header fw-bold bg-secondary text-white d-flex align-items-center gap-2">
        Control Manual de Pacientes
      </div>
      <div class="card-body">
        <div class="row g-2 align-items-stretch">
          <!-- Botones para agregar pacientes por nivel -->
          <div class="col-12 col-md-8">
            <p class="fw-semibold mb-2 text-muted small text-uppercase">Registrar paciente manualmente:</p>
            <div class="d-flex flex-wrap gap-2">
              <button
                v-for="n in nivelesTriaje"
                :key="n.nivel"
                @click="agregarPacienteManual(n.nivel)"
                :class="['btn btn-sm fw-bold', n.btnClass]"
                :title="n.desc"
              >
                + {{ n.label }}
              </button>
            </div>
            <small class="text-muted mt-1 d-block">
              Cada botón agrega un paciente con el nivel seleccionado a la simulación activa.
            </small>
          </div>
          <!-- Divisor vertical -->
          <div class="col-12 col-md-1 d-none d-md-flex justify-content-center">
            <div class="vr"></div>
          </div>
          <!-- Botón de deadlock -->
          <div class="col-12 col-md-3 d-flex flex-column justify-content-center">
            <p class="fw-semibold mb-2 text-muted small text-uppercase">Demostración:</p>
            <button
              id="btn-simular-deadlock"
              @click="simularDeadlock"
              class="btn btn-danger fw-bold"
              title="Crea dos pacientes Nivel 1 que competirán por los mismos recursos generando un deadlock"
            >
              Simular Deadlock
            </button>
            <small class="text-muted mt-1 d-block">
              Genera 2 pacientes Críticos en competencia circular de recursos.
            </small>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Fila principal ─────────────────────────────────────────────── -->
    <div class="row g-3">

      <!-- Recursos del Hospital -->
      <div class="col-12 col-lg-3">
        <div class="card h-100">
          <div class="card-header fw-bold">Estado de Recursos</div>
          <div class="card-body">
            <div v-for="(data, nombre) in recursos" :key="nombre" class="mb-3">
              <div class="d-flex justify-content-between fw-medium mb-1" style="font-size:0.85rem">
                <span>{{ nombre }}</span>
                <span :class="data.usados >= data.total ? 'text-danger fw-bold' : 'text-success'">
                  {{ data.total - data.usados }} / {{ data.total }} libres
                </span>
              </div>
              <div class="progress" style="height:0.55rem;">
                <div
                  class="progress-bar"
                  :class="data.usados >= data.total ? 'bg-danger' : (data.usados / data.total > 0.7 ? 'bg-warning' : 'bg-success')"
                  role="progressbar"
                  :style="{ width: `${(data.usados / data.total) * 100}%` }"
                ></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Columna central: Pacientes en Atención + Logs -->
      <div class="col-12 col-lg-6 d-flex flex-column gap-3">

        <!-- Pacientes en Atención -->
        <div class="card">
          <div class="card-header fw-bold">Pacientes en Atención ({{ pacientesAtendidos.length }})</div>
          <div class="card-body" style="max-height:320px;overflow-y:auto;">
            <p v-if="pacientesAtendidos.length === 0" class="text-muted fst-italic mb-0">No hay pacientes en atención actualmente.</p>
            <div class="row row-cols-1 row-cols-md-2 g-2">
              <div v-for="p in pacientesAtendidos" :key="p.idPaciente" class="col">
                <div class="border rounded p-2 bg-white shadow-sm">
                  <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="fw-bold" style="font-size:0.9rem;">Paciente #{{ p.idPaciente }}</span>
                    <span :class="['badge', getNivelBadge(p.prioridad)]">
                      N{{ p.prioridad }}
                    </span>
                  </div>
                  <div class="progress mb-1" style="height:0.5rem;">
                    <div
                      class="progress-bar"
                      :class="getNivelProgressColor(p.prioridad)"
                      role="progressbar"
                      :style="{ width: `${p.progreso}%` }"
                    ></div>
                  </div>
                  <small class="text-muted">
                    {{ Math.round(p.progreso) }}% — {{ p.triaje.replace('NIVEL_','').replace(/_/g,' ') }}
                  </small>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Log de Eventos -->
        <div class="card">
          <div class="card-header fw-bold text-white bg-dark">Log de Eventos del Sistema</div>
          <div class="card-body bg-dark text-success p-2 overflow-auto" style="height:220px;font-family:monospace;font-size:0.78rem;">
            <div v-for="(l, i) in logs" :key="i" class="mb-0" style="border-bottom:1px solid #333;padding:2px 0;">{{ l }}</div>
          </div>
        </div>
      </div>

      <!-- Cola de Espera -->
      <div class="col-12 col-lg-3">
        <div class="card h-100">
          <div class="card-header fw-bold">
            Cola de Espera
            <span class="badge bg-secondary ms-1">{{ pacientesEnEspera.length }}</span>
          </div>
          <div class="card-body p-2" style="overflow-y:auto;max-height:500px;">
            <p v-if="pacientesEnEspera.length === 0" class="text-muted fst-italic px-2 mb-0">Sala vacía.</p>
            <ul class="list-group list-group-flush">
              <li
                v-for="(p, idx) in pacientesEnEspera"
                :key="p.idPaciente"
                class="list-group-item d-flex justify-content-between align-items-center px-2 py-1"
                :class="getTriajeColor(p.prioridad)"
                style="font-size:0.85rem;"
              >
                <span>
                  <span class="me-1 text-muted" style="font-size:0.75rem;">{{ idx + 1 }}.</span>
                  <strong>Paciente #{{ p.idPaciente }}</strong>
                </span>
                <span class="badge bg-white text-dark border">N{{ p.prioridad }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Modal de Deadlock ──────────────────────────────────────────── -->
    <div v-if="deadlock.activo" class="modal show d-block" tabindex="-1" style="background:rgba(0,0,0,0.7);z-index:9999;">
      <div class="modal-dialog modal-dialog-centered modal-lg">
        <div class="modal-content border border-danger border-3">
          <div class="modal-header bg-danger text-white">
            <h4 class="modal-title fw-bold">INTERBLOQUEO DETECTADO</h4>
          </div>
          <div class="modal-body">
            <div class="alert alert-danger mb-3">
              <strong>El sistema está en estado de deadlock.</strong>
              Dos pacientes compiten de forma circular por los mismos recursos y ninguno puede avanzar.
            </div>

            <!-- Detalles de los pacientes en deadlock -->
            <div class="row g-3 mb-4">
              <div v-for="d in deadlock.detalles" :key="d.idPaciente" class="col-12 col-md-6">
                <div class="card border-danger">
                  <div class="card-header bg-danger text-white fw-bold">
                    Paciente #{{ d.idPaciente }}
                  </div>
                  <div class="card-body">
                    <p class="mb-1">
                      <span class="badge bg-success me-1">Retiene</span>
                      <strong>{{ d.recursoRetenido }}</strong>
                    </p>
                    <p class="mb-0">
                      <span class="badge bg-danger me-1">Necesita</span>
                      <strong>{{ d.recursoFaltante }}</strong>
                      <span class="text-muted small"> (ocupado por el otro)</span>
                    </p>
                  </div>
                </div>
              </div>
            </div>

            <p class="fw-semibold text-center text-dark">
              Seleccione el paciente que recibirá los recursos. El otro regresará a la cola de espera:
            </p>
            <div class="d-flex gap-3 justify-content-center">
              <button
                v-for="id in deadlock.involucrados"
                :key="id"
                @click="resolverDeadlock(id)"
                class="btn btn-danger btn-lg fw-bold px-4"
              >
                Favorecer Paciente #{{ id }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
/* Badge de nivel por prioridad */
</style>
