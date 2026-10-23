# Consistencia financiera bajo concurrencia

Aplicación de retiros y transferencias con Java 21, Spring Boot y PostgreSQL.
Controla operaciones simultáneas sobre las mismas cuentas para evitar saldos negativos,
duplicados y transferencias parciales. La última ejecución verificada aprobó **77 pruebas**.

## Requisitos

- JDK 21.
- Docker y Docker Compose disponibles.
- Internet para la primera descarga de dependencias e imágenes.

El proyecto incluye Maven Wrapper y la configuración de PostgreSQL en Docker.

## Ejecutar

Desde la carpeta del proyecto:

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`. PostgreSQL queda en `localhost:5433`, base `financial`.
Las credenciales locales están en `compose.yaml`. La conexión admite las variables
`DB_URL`, `DB_USERNAME` y `DB_PASSWORD`.

Al iniciar, Flyway aplica las migraciones. En una base nueva crea la cuenta 1 con
S/ 10,000.00 y la cuenta 2 con S/ 0.00. Los datos existentes se conservan.

## Operaciones

Con la aplicación iniciada, desde otra terminal:

```sh
curl -i http://localhost:8080/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"key":"withdraw-1","sourceId":1,"amount":5.00}'

curl -i http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{"key":"transfer-1","sourceId":1,"destinationId":2,"amount":10.00}'
```

Sobre datos nuevos, estos ejemplos dejan S/ 9,985.00 en la cuenta 1 y S/ 10.00 en la cuenta 2.
Una operación exitosa devuelve HTTP 200 y `{"id":"<UUID>","status":"SUCCESS","reason":null}`.

| Situación | Respuesta |
|---|---|
| Misma clave y mismos datos | No duplica un movimiento confirmado |
| Misma clave con otros datos | HTTP 409 |
| Importe inválido | HTTP 400 |
| Rechazo, por ejemplo por saldo insuficiente | HTTP 422 |
| Fallo técnico o resultado sin confirmar | HTTP 503 |

Los importes deben ser positivos y expresar céntimos exactos, sin redondeo.
Las cuentas de una transferencia deben ser distintas.

**Ante un HTTP 503, conservar la misma clave y repetir la solicitud para recuperar el resultado
o reintentar.** Esa respuesta no confirma que el movimiento haya fallado.

## Pruebas

```sh
./mvnw -B test
```

Solo los escenarios concurrentes:

```sh
./mvnw -B -Dtest=ConcurrencyTest test
```

Docker debe estar activo. Testcontainers crea bases PostgreSQL temporales: no modifica la base
de Compose. Los reportes quedan en `target/surefire-reports/`.
Para Docker Desktop en Linux, está disponible la [configuración específica](docs/test-results.md#docker-desktop-en-linux).

## Documentación

- [Nota técnica](docs/technical-note.md): decisiones y límites.
- [Flujos y conciliación SQL](docs/transaction-flows.md): transacciones y comprobación de saldos.
- [Resultados de pruebas](docs/test-results.md): escenarios, entorno y método.

## Detener

Detener la aplicación con Ctrl+C y ejecutar `docker compose stop`. Los datos se conservan.


