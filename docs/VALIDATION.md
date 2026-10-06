# Validación

Trabajo realizado: 2026-10-05–2026-10-06 (America/Lima).

- Backend: Java 21.0.6, Maven 3.9.9. Pruebas locales ejecutadas: 10, sin fallos; confirmadas también en GitHub Actions.
- Angular: build de producción con comprobación estricta de TypeScript/templates.
- Docker Compose: configuración validada con docker compose config --quiet.
- Docker Engine local no disponible. **Docker Compose sí se ejecutó y pasó en GitHub Actions**, con API, Angular y servicios auxiliares reales.
- Código verificado: `c3315c5`. [Ejecución exitosa: backend + frontend + stack](https://github.com/LuisDeveloper-Fer/async-transaction-service/actions/runs/37416760781).
- El commit posterior de capturas/documentación no modifica el código validado.


## Reproducir

```bash
mvn clean package
cd frontend && npm ci && npm run build
cd ..
docker compose up -d --build
python scripts/smoke.py
docker compose down
```

Se usan datos ficticios. El smoke test crea registros nuevos; no debe ejecutarse contra entornos ajenos al laboratorio.
