# API — Async Transaction Service

Base: http://localhost:8080. Content-Type: application/json.

| Método | Ruta | Contrato |
| --- | --- | --- |
| POST | `/api/transactions` | 202; 400 inválido; 429 rate limit; 503 capacidad |
| GET | `/api/transactions/{id}` | Estado; 404 inexistente o expirado |
| GET | `/api/transactions` | Últimos 50 registros |
| GET | `/api/system` | Cola, capacidad y circuito |
| GET | `/actuator/prometheus` | Métricas |

## Request
```json
{
  "amount": 125.5,
  "currency": "PEN",
  "scenario": "SUCCESS"
}
```

## Response (campos relevantes)
```json
{
  "id": "c6057e62-2782-456d-9157-b33440238078",
  "status": "QUEUED",
  "correlationId": "interview-01",
  "error": null
}
```

## Errores
400 indica validación o formato inválido; 404 indica recurso inexistente. Los estados específicos se detallan en la tabla. ProblemDetail se usa para errores de negocio y validación donde aplica; autenticación puede devolver cuerpo vacío y WWW-Authenticate. Los clientes deben usar códigos, no parsear mensajes internos.

Un fallo remoto después del 202 aparece en status=FAILED y error. 429/503 de admisión incluyen Retry-After: 1. Escenarios: SUCCESS, SLOW (3 s), ERROR (500), NEVER y RATE_LIMIT (5/s en el simulador). Correlation ID: 1–64 caracteres alfanuméricos, punto, guion o underscore. traceparent: W3C versión 00, IDs hex no nulos.
Importe positivo: máximo 9 enteros y 2 decimales; moneda PEN, USD o EUR.
