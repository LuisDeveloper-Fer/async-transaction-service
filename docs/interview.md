# Demostración en cinco minutos

## Problema — 30 segundos
Un proveedor lento puede agotar conexiones y bloquear una API. Este servicio desacopla la admisión de una transacción de su entrega HTTP, con límites explícitos de memoria, concurrencia y tiempo.

## Experimento — 2 minutos
Envía SUCCESS, SLOW, ERROR, NEVER y RATE_LIMIT. Compara el 202 inicial con el estado final. Provoca varios fallos para abrir el circuito y observa su recuperación.

## Decisión — 1 minuto
La suscripción pertenece al worker, no al controlador. La admisión ocupa un permiso hasta terminar la entrega. Un timeout deja el resultado remoto desconocido: no reintentamos operaciones financieras sin un contrato de idempotencia. La cola volátil mantiene pequeño el laboratorio y hace explícito el costo de no usar una outbox.

## Discusión
- ¿Qué operación es atómica?
- ¿Qué ocurre entre confirmar una escritura y enviar una respuesta?
- ¿Qué impide agotar recursos?
- ¿Qué cambia al ejecutar dos réplicas?
- ¿Qué mide el dashboard y qué no permite concluir?

## Límites que conviene explicar
Cola y registros en memoria; reiniciar pierde trabajo. Retención terminal de 15 minutos, máximo 1000 registros, expulsando el terminal más antiguo al llenar el almacén. No hay entrega durable. Se propaga traceparent W3C, pero no se exportan spans. Límites y timeouts configurables mediante dispatch.*.
