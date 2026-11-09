# Microservicio: AI-Powered Fleet Allocation (TruckCar)

## 1. Definición del Algoritmo y Estrategia de Asignación

### ¿Es algoritmo ponderado, IA real o una mezcla?
El microservicio implementa un **Motor de Optimización Multicriterio Determinista Ponderado (Weighted Sum Model Heuristic)** con arquitectura extensible para enriquecimiento predictivo híbrido vía eventos Kafka (`AgentScoreReadyEvent`).

En producción y entornos de transporte de carga pesada, la toma de decisiones para asignación de flota no puede delegarse a modelos no deterministas ("cajas negras" o LLMs generativos) que carecen de garantías matemáticas de reproducibilidad, auditabilidad y estricto cumplimiento regulatorio (RUNT, cadena de frío, peso bruto vehicular según resoluciones del Ministerio de Transporte). Por ello, la estrategia seleccionada garantiza:
1. **Determinismo y reproducibilidad matemática:** Para las mismas condiciones de entrada (ubicación, carga, conductores disponibles), la recomendación es 100% idéntica y consistente.
2. **Explicabilidad total:** Cada candidato evaluado genera un puntaje numérico claro junto con el desglose de sus componentes y el motivo explícito de elegibilidad o rechazo.
3. **Latencia predecible de milisegundos:** Evaluación en memoria de cientos de conductores en tiempo real sin latencias externas de inferencia ni riesgo de caídas de servicios de IA de terceros.
4. **Filtros duros de seguridad operacional y legal:** Descarte inmediato e inviolable si el conductor no cumple con los requisitos mínimos de ley o de la carga.

---

## 2. Formulación Matemática de la Estrategia

### A. Filtros Duros de Viabilidad (Restricciones No Negociables)
Un camión o conductor es marcado como `eligible = false` (puntaje 0.0) de forma inmediata ante cualquiera de las siguientes condiciones:
1. **Conductor no disponible:** `busyReason != null` (en viaje, en descanso o no disponible).
2. **RUNT no vigente:** `runtValid == false` (el vehículo no cuenta con documentación legal activa para transitar en territorio nacional).
3. **Cadena de frío no certificada:** Si `load.requiredTruckType == "REFRIGERADO"` y `truck.coldChainCertified != true`.
4. **Capacidad insuficiente:** `truck.capacityTons < load.weightTons` o sin capacidad registrada.
5. **Incompatibilidad de tipología de vehículo:** Si el tipo de camión requerido no coincide con el vehículo asignado al conductor (`truckType != requiredTruckType`).

### B. Función de Puntaje Multicriterio (0.0 - 100.0)
Para los conductores que superan los filtros duros, el puntaje se calcula como una combinación ponderada de tres factores normalizados:

$$\text{Puntaje} = \left( W_{\text{proximidad}} \cdot S_{\text{proximidad}} + W_{\text{capacidad}} \cdot S_{\text{capacidad}} + W_{\text{calificación}} \cdot S_{\text{calificación}} \right) \times 100$$

Donde:
* **$W_{\text{proximidad}} = 0.50$ (50% Proximidad):**
  Calculada mediante la distancia ortodrómica de Haversine ($d_{\text{km}}$) entre las coordenadas actuales del camión y el punto de cargue/origen. Aplica decaimiento exponencial:
  $$S_{\text{proximidad}} = e^{-\frac{d_{\text{km}}}{200.0}}$$
  *Ventaja:* A 0 km el factor es 1.0; a 100 km es ~0.61; a 300 km es ~0.22. Evita empates artificiales y cortes lineales abruptos.
* **$W_{\text{capacidad}} = 0.30$ (30% Eficiencia de Capacidad):**
  Premia el óptimo aprovechamiento del camión sin sobrecargarlo:
  $$S_{\text{capacidad}} = \frac{\text{Peso Carga (Ton)}}{\text{Capacidad Camión (Ton)}}$$
* **$W_{\text{calificación}} = 0.20$ (20% Desempeño y Calificación):**
  Normaliza la reputación histórica del conductor (escala de 1 a 5 estrellas):
  $$S_{\text{calificación}} = \frac{\text{Rating}}{5.0}$$

### C. Criterio de Desempate (Ranking Determinista)
Cuando dos o más conductores obtienen puntajes similares, el ordenamiento se rige de manera estricta y reproducible por:
1. **Mayor puntaje global:** `score DESC`
2. **Menor distancia muerta (deadhead):** `deadheadDistanceKm ASC`
3. **Identificador del conductor:** `driverId ASC` (desempate lexicográfico para evitar dependencia del orden aleatorio de bases de datos o caché).

---

## 3. Endpoints REST (Torre de Control)

Todos los endpoints administrativos requieren el rol `COORDINADOR` o `ADMIN` en la cabecera `X-Auth-Role`.

* `POST /api/v1/fleet-allocation/optimize`
  * Ejecuta la evaluación multicriterio sobre una lista explícita de camiones candidatos contra una carga dada.
* `POST /api/v1/fleet-allocation/match/{loadId}`
  * Ejecuta la búsqueda automática consultando los conductores activos en `fleets-drivers`, los evalúa, genera la decisión y notifica a `Load-Allocation`.
* `GET /api/v1/fleet-allocation/history/{loadId}`
  * Consulta el histórico de decisiones y trazabilidad de matching para una carga específica.

---

## 4. Pruebas y Validación

El conjunto de pruebas automatizadas en `src/test/java` valida:
* **`MulticriteriaAllocationVerificationTest`:** Prueba end-to-end con múltiples conductores con atributos dispares (cercanía vs calificación vs eficiencia), restricciones de refrigeración y RUNT, verificando la consistencia y reproducibilidad de las recomendaciones.
* **`FleetScoringOptimizerTest`:** Pruebas unitarias de decaimiento exponencial, filtros duros de capacidad y desempate.
* **`FleetAllocationMatchTest`:** Integración con la torre de control y resolución de conflictos de estado.
