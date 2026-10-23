# Resultados de pruebas

**77 pruebas aprobadas, sin fallos, errores ni omisiones.**
La ejecución completa en Docker Desktop terminó con código de salida 0 en 28.355 s.

Entorno: Java 21.0.2, Spring Boot 3.5.9, Docker Desktop 29.8.2 (3,558 MB de RAM),
Testcontainers 1.21.4, PostgreSQL `postgres:17.7` y Surefire 3.5.4.
Las pruebas usan bases temporales aisladas de Compose. Flyway aplica las migraciones V1 y V2.
Sin Docker, las pruebas fallan; no se omiten.

| Clase | Pruebas |
|---|---:|
| ConcurrencyTest | 8 |
| WithdrawalTest | 17 |
| TransferTest | 22 |
| FailureHandlingTest | 18 |
| MoneyTest | 11 |
| ApplicationTest | 1 |
| **Total** | **77** |

## Docker Desktop en Linux

El siguiente comando selecciona Docker Desktop para esta ejecución y realiza una compilación
limpia antes de ejecutar toda la suite. Fue el comando usado para el resultado indicado:

```sh
DOCKER_HOST="$(docker context inspect desktop-linux --format '{{ .Endpoints.docker.Host }}')" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -Ddocker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy clean test
```

No cambia la configuración global de Docker ni los datos de Compose.

## Escenarios concurrentes

Importes en soles. Las cuentas A y B comienzan con 10,000 y 0, salvo indicación.
SUCCESS y REJECTED cuentan operaciones distintas guardadas en la base, no llamadas recibidas.
Los ocho escenarios pasaron.

| Escenario | Solicitudes × importe | SUCCESS / REJECTED | Saldo final A / B |
|---|---|---:|---|
| Retiros | 1,000 × 5 | 1,000 / 0 | 5,000 / 0 |
| Transferencias | 500 × 10 | 500 / 0 | 5,000 / 5,000 |
| Retiros | 10,000 × 1 | 10,000 / 0 | 0 / 0 |
| Transferencias | 5,000 × 1 | 5,000 / 0 | 5,000 / 5,000 |
| Sobredemanda de retiros; A=100 | 128 × 5 | 20 / 108 | 0 / 0 |
| Sobredemanda de transferencias; A=100 | 128 × 5 | 20 / 108 | 0 / 100 |
| Misma clave de retiro | 128 × 5 | 1 / 0 | 9,995 / 0 |
| Transferencias en ambos sentidos; A=B=10,000 | 512 × 10 | 512 / 0 | 10,000 / 10,000 |

## Método y comprobaciones

Las cargas llaman al orquestador público `OperationOrchestrator` y usan PostgreSQL real.
Todas las tareas esperan una señal de inicio común; se permiten hasta 64 llamadas activas
con 10 conexiones a la base. No son miles de llamadas HTTP simultáneas.

Las comprobaciones consultan SQL: comparan respuestas y registros, cuentan operaciones e
intentos, suman importes, verifican saldos no negativos y [concilian cada cuenta](transaction-flows.md#conciliar-desde-sql).
En estos escenarios, cada operación distinta tiene un intento coincidente. El plazo global
por carga es de 240 s; el límite JUnit es de 300 s y el cierre de trabajadores, de 10 s.

Las demás pruebas cubren idempotencia HTTP en ambos endpoints, rechazo de datos distintos
con la misma clave, validación monetaria sin cambios persistidos, reversión tras el débito,
auditoría de fallos y resolución de resultados inciertos. También verifican el máximo de
tres intentos en transacciones nuevas y la interrupción de la espera entre intentos.

## Alcance

El error de bloqueo PostgreSQL 55P03 se provoca realmente. Los fallos entre débito y crédito
y la pérdida de confirmación se simulan dentro de las pruebas; no se ensayaron cortes reales
de red. La auditoría no está garantizada ante una caída de la aplicación o de la base.

El tiempo informado corresponde a una ejecución de integridad, no a un benchmark ni a una
medición de capacidad. Las pruebas no demuestran atomicidad entre bases distintas ni
seguridad para un despliegue bancario.

[Nota técnica](technical-note.md) · [Flujos de operación](transaction-flows.md) ·
[Instrucciones de ejecución](../README.md)
