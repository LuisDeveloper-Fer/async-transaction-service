# Experimentos de resiliencia

| Control | Valor inicial | Qué protege |
| --- | --- | --- |
| Rate limit de admisión | 10/s sin espera | Tasa por instancia |
| Concurrencia | 4 | Llamadas simultáneas |
| Cola | 16 | Memoria pendiente |
| Pool HTTP | 4 conexiones | Sockets reutilizables |
| Pendientes del pool | 4 / 250 ms | Espera de conexión |
| Connect timeout | 500 ms | Establecimiento TCP |
| Response timeout | 1500 ms | Inactividad de lectura |
| Deadline | 2 s | Llamada completa, incluyendo adquirir conexión |
| Circuit breaker | ventana 10, mínimo 5, 50% | Proveedor en fallo |
| Recuperación | 5 s, 2 pruebas HALF_OPEN | Reintroducción gradual |

`dispatch.concurrency`, `dispatch.queue-capacity`, `dispatch.rate-per-second`, `dispatch.connect-timeout`, `dispatch.response-timeout`, `dispatch.deadline`, `dispatch.acquire-timeout` se pueden sobrescribir en YAML o con variables como DISPATCH_CONCURRENCY. El pool se alinea con la concurrencia.

## Escenarios

- SUCCESS: entrega correcta.
- SLOW: el proveedor espera 3 segundos; el response timeout gana antes.
- ERROR: HTTP 500.
- NEVER: acepta la solicitud pero no responde; el cliente cancela al vencer el timeout.
- RATE_LIMIT: el simulador permite cinco solicitudes por segundo en este escenario; excederlas produce 429 remoto. No confundirlo con el 429 de admisión.

Para mostrar saturación, configura DISPATCH_RATE_PER_SECOND=100 y envía una ráfaga con SLOW. La API devuelve 503 al ocupar la cola y los slots. Para demostrar admisión limitada, conserva el valor 10 y envía más de diez solicitudes en una ventana. El controlador no espera al proveedor.

En un solo proveedor, los escenarios comparten circuit breaker. Si se abre por errores, SUCCESS también puede terminar en CIRCUIT_OPEN hasta que pase la espera. Un 429 remoto se registra como fallo de entrega pero se excluye del porcentaje del circuit breaker.

## Observabilidad

Grafana incluye accepted TPS, resultados, p95/p99 de entrega, cola, in-flight, pool y circuito. Las consultas rate necesitan varias muestras: genera tráfico y espera unos segundos. Los IDs están en logs y respuestas; nunca en etiquetas de métricas. La latencia de entrega no incluye espera en cola; `transactions.end.to.end` sí la incluye.

## Garantías

202 significa trabajo admitido en memoria. Fire-and-forget desacopla la respuesta, pero el worker sigue registrando errores. No hay exactly-once, entrega durable ni cancelación remota garantizada: el proveedor puede completar una operación después de que el cliente haya vencido su timeout.
