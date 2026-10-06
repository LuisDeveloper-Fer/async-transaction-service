# Demo pública
La versión GitHub Pages ejecuta una simulación en memoria dentro del navegador. No ejecuta Spring Boot ni mide hilos, HTTP, resiliencia o transacciones reales. El aviso permanece visible. No se envían datos a un servidor; se borran al recargar. Máximo 100 registros por sesión.

El modo local sigue conectado a la API Java. Usa ?demo=true para revisar la demo localmente. Los adaptadores están aislados en frontend/src/demo.ts. La demo permite explorar formularios y estados; la validación de arquitectura corresponde a las pruebas Java y Docker Compose del repositorio.

Hosting: https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
