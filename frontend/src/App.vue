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

// Colores por prioridad
const getTriajeColor = (prioridad) => {
  switch(prioridad) {
    case 1: return 'bg-red-500 text-white';
    case 2: return 'bg-orange-500 text-white';
    case 3: return 'bg-yellow-400 text-black';
    case 4: return 'bg-green-500 text-white';
    case 5: return 'bg-blue-500 text-white';
    default: return 'bg-gray-300 text-black';
  }
};

const getTriajeTextColor = (prioridad) => {
  switch(prioridad) {
    case 1: return 'text-red-600';
    case 2: return 'text-orange-600';
    case 3: return 'text-yellow-600';
    case 4: return 'text-green-600';
    case 5: return 'text-blue-600';
    default: return 'text-gray-600';
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
    // Ordenar por prioridad
    pacientesEnEspera.value.sort((a, b) => a.prioridad - b.prioridad || a.idPaciente - b.idPaciente);
  } 
  else if (evento.tipo === 'RECURSOS_ASIGNADOS') {
    // Remover de la cola
    const idx = pacientesEnEspera.value.findIndex(p => p.idPaciente === evento.datos.idPaciente);
    if (idx !== -1) pacientesEnEspera.value.splice(idx, 1);
    
    // Agregar a atendidos
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
  <div class="min-h-screen bg-slate-50 text-slate-800 p-6 font-sans relative">
    
    <!-- HEADER -->
    <header class="mb-8 border-b pb-4 flex justify-between items-center">
      <h1 class="text-3xl font-bold text-green-800">Hospital Central - Emergencias</h1>
      <div class="bg-white px-4 py-2 rounded-lg shadow border border-green-100">
        <span class="text-sm text-slate-500 uppercase font-semibold tracking-wider">Atendidos: </span>
        <span class="text-2xl font-bold text-green-700">{{ stats.totalAtendidos }}</span>
      </div>
    </header>

    <div class="grid grid-cols-1 lg:grid-cols-4 gap-6">
      
      <!-- ESTADO DE RECURSOS -->
      <div class="col-span-1 bg-white p-5 rounded-xl shadow-sm border border-slate-200">
        <h2 class="text-xl font-bold text-slate-700 mb-4 border-b pb-2">Recursos</h2>
        <div class="space-y-4">
          <div v-for="(data, nombre) in recursos" :key="nombre">
            <div class="flex justify-between text-sm mb-1 font-medium">
              <span>{{ nombre }}</span>
              <span :class="data.usados >= data.total ? 'text-red-600 font-bold' : 'text-green-600'">
                {{ data.total - data.usados }} Disp. / {{ data.total }}
              </span>
            </div>
            <div class="w-full bg-slate-200 rounded-full h-2.5">
              <div class="h-2.5 rounded-full transition-all duration-300"
                   :class="data.usados >= data.total ? 'bg-red-500' : 'bg-green-500'"
                   :style="{ width: `${(data.usados / data.total) * 100}%` }">
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- PACIENTES ATENDIDOS Y LOGS -->
      <div class="col-span-1 lg:col-span-2 flex flex-col gap-6">
        
        <!-- Atendidos -->
        <div class="bg-white p-5 rounded-xl shadow-sm border border-slate-200 flex-1">
          <h2 class="text-xl font-bold text-slate-700 mb-4 border-b pb-2">Pacientes en Atención</h2>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div v-if="pacientesAtendidos.length === 0" class="text-slate-400 italic">No hay pacientes en atención.</div>
            <div v-for="p in pacientesAtendidos" :key="p.idPaciente" 
                 class="border rounded-lg p-4 bg-slate-50 shadow-sm relative overflow-hidden">
              <div class="flex justify-between items-center mb-2">
                <span class="font-bold text-slate-700">Paciente {{ p.idPaciente }}</span>
                <span :class="['text-xs font-bold uppercase', getTriajeTextColor(p.prioridad)]">{{ p.triaje.replace('NIVEL_', '') }}</span>
              </div>
              <!-- Progress bar exacta -->
              <div class="w-full bg-slate-200 rounded-full h-3">
                <div class="bg-green-500 h-3 rounded-full transition-none" :style="{ width: `${p.progreso}%` }"></div>
              </div>
            </div>
          </div>
        </div>

        <!-- Terminal Logs -->
        <div class="bg-slate-900 text-green-400 p-5 rounded-xl shadow-sm h-64 overflow-hidden flex flex-col">
          <h2 class="text-lg font-bold text-white mb-2 border-b border-slate-700 pb-1">Logs del Sistema</h2>
          <div class="overflow-y-auto flex-1 font-mono text-sm pr-2 space-y-1">
            <div v-for="(l, i) in logs" :key="i" class="opacity-90">{{ l }}</div>
          </div>
        </div>

      </div>

      <!-- COLA DE ESPERA -->
      <div class="col-span-1 bg-white p-5 rounded-xl shadow-sm border border-slate-200">
        <h2 class="text-xl font-bold text-slate-700 mb-4 border-b pb-2">Cola de Espera ({{ pacientesEnEspera.length }})</h2>
        <div class="space-y-3 overflow-y-auto max-h-[600px] pr-2">
          <div v-if="pacientesEnEspera.length === 0" class="text-slate-400 italic">Sala vacía.</div>
          <div v-for="p in pacientesEnEspera" :key="p.idPaciente"
               class="flex justify-between items-center p-3 rounded-lg border border-slate-100 shadow-sm"
               :class="getTriajeColor(p.prioridad)">
            <span class="font-bold">Paciente {{ p.idPaciente }}</span>
            <span class="text-xs font-bold tracking-widest">NIVEL {{ p.prioridad }}</span>
          </div>
        </div>
      </div>
      
    </div>

    <!-- MODAL DE DEADLOCK -->
    <div v-if="deadlock.activo" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-70 backdrop-blur-sm">
      <div class="bg-white rounded-2xl p-8 max-w-md w-full shadow-2xl border-4 border-red-500 transform animate-bounce-short">
        <div class="text-center">
          <div class="text-red-500 text-5xl font-black mb-4">¡ALERTA!</div>
          <h2 class="text-2xl font-bold text-slate-800 mb-2">{{ deadlock.mensaje }}</h2>
          <p class="text-slate-600 mb-6">El sistema ha entrado en una espera circular. Por favor, selecciona qué paciente debe recibir los recursos faltantes para continuar la simulación.</p>
          
          <div class="space-y-4">
            <button v-for="id in deadlock.involucrados" :key="id" 
                    @click="resolverDeadlock(id)"
                    class="w-full bg-red-100 hover:bg-red-500 text-red-800 hover:text-white border border-red-300 font-bold py-3 px-4 rounded-xl transition-colors">
              Favorecer Paciente {{ id }}
            </button>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<style>
.animate-bounce-short {
  animation: bounce-short 0.5s ease-out 1;
}

@keyframes bounce-short {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}
</style>
