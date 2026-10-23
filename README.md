# Consistencia financiera bajo concurrencia

Prueba técnica con Java 21, Spring Boot y PostgreSQL: retiros y transferencias sin saldos
negativos, movimientos duplicados ni cambios parciales. **77 pruebas aprobadas.**

## Requisitos

- JDK 21.
- Docker y Docker Compose disponibles.
- Internet para la primera descarga de dependencias e imágenes.

No hace falta instalar Maven ni PostgreSQL: se incluyen Maven Wrapper y la configuración de Docker.
En IntelliJ, abrir `pom.xml` y seleccionar JDK 21.

## Ejecutar

Desde la carpeta del proyecto:

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

La API queda en `http://localhost:8080`. PostgreSQL se publica en `localhost:5433`, base
`financial`. Las credenciales locales están en `compose.yaml`; la conexión puede cambiarse
con `DB_URL`, `DB_USERNAME` y `DB_PASSWORD`.

Flyway prepara y actualiza la estructura de la base al iniciar. En una base nueva crea la cuenta 1
con S/ 10,000.00 y la cuenta 2 con S/ 0.00. Los datos existentes no se reinician.

## Probar las operaciones

En otra terminal, con la aplicación iniciada:

```sh
curl -i http://localhost:8080/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"key":"withdraw-1","sourceId":1,"amount":5.00}'

curl -i http://localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -d '{"key":"transfer-1","sourceId":1,"destinationId":2,"amount":10.00}'
```

Sobre datos nuevos, quedan S/ 9,985.00 en la cuenta 1 y S/ 10.00 en la cuenta 2.
El éxito devuelve HTTP 200 y `{"id":"<UUID>","status":"SUCCESS","reason":null}`.

- Repetir una clave con los mismos datos no vuelve a mover dinero.
- Usar la misma clave con otros datos devuelve HTTP 409.
- Un importe inválido devuelve HTTP 400; una operación rechazada, por ejemplo por saldo insuficiente, HTTP 422.
- Un fallo técnico puede devolver HTTP 503. Eso no demuestra que el movimiento haya fallado:
  **conservar la misma clave para consultar el resultado o reintentar la operación**.

Los importes deben ser positivos y representar céntimos exactos, sin redondeo.
Las cuentas de una transferencia deben ser distintas.

## Ejecutar las pruebas

```sh
./mvnw -B test
```

Solo los escenarios concurrentes:

```sh
./mvnw -B -Dtest=ConcurrencyTest test
```

Las pruebas usan PostgreSQL temporal mediante Testcontainers: **no modifican la base de Compose**.
Docker debe estar funcionando. Los reportes quedan en `target/surefire-reports/`.
Para Docker Desktop en Linux, consultar la [configuración de pruebas](docs/test-results.md#docker-desktop-en-linux).

## Organización y documentos

- `controller`: recibe solicitudes HTTP y devuelve respuestas.
- `service`: ejecuta las operaciones y asegura que se apliquen completas o no se apliquen.
- `repository`: contiene las consultas a la base de datos (SQL).
- `model`: define los datos y sus validaciones.

[Nota técnica](docs/technical-note.md) · [Diagrama y conciliación SQL](docs/transaction-flows.md) ·
[Resultados y escenarios](docs/test-results.md)

## Detener el entorno

Detener la aplicación con Ctrl+C y luego ejecutar `docker compose stop`. Los datos se conservan.
La API y sus credenciales son para uso local: **no exponerla públicamente**.
