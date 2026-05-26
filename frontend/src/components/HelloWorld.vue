<script setup>
import { ref } from 'vue';
const count = ref(0);
</script>

<template>
  <div class="container py-4">
    <div class="card text-center">
      <div class="card-header bg-success text-white">
        <h2 class="mb-0">Dashboard Hospitalario</h2>
      </div>
      <div class="card-body">
        <p class="card-text">Bienvenido al simulador de emergencias.</p>
        <button class="btn btn-primary" @click="count++">Count is {{ count }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* No extra styles needed */
</style>
