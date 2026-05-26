<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { Client } from '@stomp/stompjs';

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
  mensaje: ''
});

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

const actualizarRecursos = (triaje, ocupando) => {
  const req = requisitosTriaje[triaje];
  if(req) {
    for (const res in req) {
      if (ocupando) recursos.value[res].usados += req[res];
      else recursos.value[res].usados -= req[res];
    }
  }
};

const procesarEvento = (evento) => {
  const time = new Date(evento.timestamp).toLocaleTimeString();
  logs.value.unshift(`[${time}] ${evento.mensaje}`);
  if (logs.value.length > 50) logs.value.pop();

  if (evento.tipo === 'NUEVO_PACIENTE') {
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
    actualizarRecursos(evento.datos.triaje, true);
  }
  else if (evento.tipo === 'RECURSOS_LIBERADOS') {
    const id = evento.datos;
    const idx = pacientesAtendidos.value.findIndex(p => p.idPaciente === id);
    if (idx !== -1) {
      const p = pacientesAtendidos.value[idx];
      actualizarRecursos(p.triaje, false);
      pacientesAtendidos.value.splice(idx, 1);
      stats.value.totalAtendidos++;
    }
  }
  else if (evento.tipo === 'DEADLOCK_DETECTADO') {
    deadlock.value.activo = true;
    deadlock.value.mensaje = 'INTERBLOQUEO DETECTADO';
    if (!deadlock.value.involucrados.includes(evento.datos.idPaciente)) {
      deadlock.value.involucrados.push(evento.datos.idPaciente);
    }
  }
  else if (evento.tipo === 'DEADLOCK_RESUELTO') {
    deadlock.value.activo = false;
    deadlock.value.involucrados = [];
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
    <!-- Header -->
    <header class="d-flex justify-content-between align-items-center mb-4 border-bottom pb-2">
      <h1 class="h3 text-success fw-bold">Hospital Central - Emergencias</h1>
      <div class="bg-white px-3 py-2 rounded shadow border border-success">
        <span class="text-muted text-uppercase small fw-semibold">Atendidos:</span>
        <span class="h4 fw-bold text-success">{{ stats.totalAtendidos }}</span>
      </div>
    </header>

    <div class="row g-3">
      <!-- Recursos -->
      <div class="col-12 col-lg-3">
        <div class="card h-100">
          <div class="card-header fw-bold">Recursos</div>
          <div class="card-body">
            <div v-for="(data, nombre) in recursos" :key="nombre" class="mb-3">
              <div class="d-flex justify-content-between text-sm fw-medium mb-1">
                <span>{{ nombre }}</span>
                <span :class="data.usados >= data.total ? 'text-danger fw-bold' : 'text-success'">
                  {{ data.total - data.usados }} Disp. / {{ data.total }}
                </span>
              </div>
              <div class="progress" style="height: 0.6rem;">
                <div class="progress-bar" :class="data.usados >= data.total ? 'bg-danger' : 'bg-success'"
                  role="progressbar"
                  :style="{ width: `${(data.usados / data.total) * 100}%` }">
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Pacientes en Atención y Logs -->
      <div class="col-12 col-lg-6 d-flex flex-column gap-3">
        <!-- Pacientes en Atención -->
        <div class="card flex-fill">
          <div class="card-header fw-bold">Pacientes en Atención</div>
          <div class="card-body">
            <div class="row row-cols-1 row-cols-md-2 g-3">
              <div v-if="pacientesAtendidos.length === 0" class="text-muted fst-italic">No hay pacientes en atención.</div>
              <div v-for="p in pacientesAtendidos" :key="p.idPaciente" class="col">
                <div class="border rounded p-3 bg-white shadow-sm position-relative overflow-hidden">
                  <div class="d-flex justify-content-between align-items-center mb-2">
                    <span class="fw-bold text-dark">Paciente {{ p.idPaciente }}</span>
                    <span :class="['text-xs fw-bold text-uppercase', getTriajeTextColor(p.prioridad)]">
                      {{ p.triaje.replace('NIVEL_', '') }}
                    </span>
                  </div>
                  <div class="progress" style="height: 0.6rem;">
                    <div class="progress-bar bg-success" role="progressbar" :style="{ width: `${p.progreso}%` }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Logs -->
        <div class="card flex-fill" style="height: 250px;">
          <div class="card-header fw-bold text-white bg-dark">Logs del Sistema</div>
          <div class="card-body bg-dark text-light p-2 overflow-auto">
            <pre class="mb-0" v-for="(l, i) in logs" :key="i" >{{ l }}</pre>
          </div>
        </div>
      </div>

      <!-- Cola de Espera -->
      <div class="col-12 col-lg-3">
        <div class="card h-100">
          <div class="card-header fw-bold">Cola de Espera ({{ pacientesEnEspera.length }})</div>
          <div class="card-body">
            <div v-if="pacientesEnEspera.length === 0" class="text-muted fst-italic">Sala vacía.</div>
            <ul class="list-group list-group-flush">
              <li v-for="p in pacientesEnEspera" :key="p.idPaciente" class="list-group-item d-flex justify-content-between align-items-center" :class="getTriajeColor(p.prioridad)">
                <span class="fw-bold">Paciente {{ p.idPaciente }}</span>
                <span class="text-xs fw-bold">NIVEL {{ p.prioridad }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>

    <!-- Modal de Deadlock -->
    <div v-if="deadlock.activo" class="modal show d-block" tabindex="-1" style="background: rgba(0,0,0,0.5);">
      <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content border border-danger">
          <div class="modal-header bg-danger text-white">
            <h5 class="modal-title">¡ALERTA!</h5>
          </div>
          <div class="modal-body text-center">
            <h4 class="mb-3">{{ deadlock.mensaje }}</h4>
            <p class="mb-4">El sistema está en deadlock. Seleccione el paciente que debe recibir los recursos.</p>
            <div class="d-grid gap-2">
              <button v-for="id in deadlock.involucrados" :key="id" @click="resolverDeadlock(id)" class="btn btn-outline-danger btn-lg">
                Favorecer Paciente {{ id }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Mantener estilos mínimos */
</style>
