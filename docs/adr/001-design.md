# ADR 001 — RESILIENCE

Estado: aceptada · 2026-10-05

## Contexto
Un proveedor lento puede agotar conexiones y bloquear una API. Este servicio desacopla la admisión de una transacción de su entrega HTTP, con límites explícitos de memoria, concurrencia y tiempo.

## Decisión
La suscripción pertenece al worker, no al controlador. La admisión ocupa un permiso hasta terminar la entrega. Un timeout deja el resultado remoto desconocido: no reintentamos operaciones financieras sin un contrato de idempotencia. La cola volátil mantiene pequeño el laboratorio y hace explícito el costo de no usar una outbox.

## Alternativas
Separar más microservicios o incorporar un broker agregaría despliegue y operación fuera del objetivo. Concentrar todo en el controlador dificultaría probar fallos y razonar sobre el contrato. Se elige una aplicación pequeña con API, casos de uso y adaptadores diferenciados.

## Consecuencias
Cola y registros en memoria; reiniciar pierde trabajo. Retención terminal de 15 minutos, máximo 1000 registros, expulsando el terminal más antiguo al llenar el almacén. No hay entrega durable. Se propaga traceparent W3C, pero no se exportan spans. Límites y timeouts configurables mediante dispatch.*.

## Validación
Validación, clasificación de errores, admisión y capacidad; HTTP real para success, 429, slow, never, circuito abierto/recuperación, IDs y métricas.
