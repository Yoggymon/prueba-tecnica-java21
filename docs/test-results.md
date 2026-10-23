# Resultados de pruebas

**Resultado final: 77 pruebas aprobadas, 0 fallos, 0 errores y 0 omisiones.**
Ejecución local con PostgreSQL real en Testcontainers aislados; no usa datos de Compose.
Entorno de las ejecuciones históricas: Java 21.0.2, Spring Boot 3.5.9, Docker 29.9.1,
Testcontainers 1.21.4, PostgreSQL `postgres:17.7`, Surefire 3.5.4; Linux, Docker con 14,981 MB de RAM.
Flyway aplicó V1/V2 en bases nuevas. Sin Docker las pruebas fallan, no se omiten.

| Comando ejecutado | Pruebas | Salida | Tiempo Maven |
|---|---:|---:|---:|
| `./mvnw -B test` antes de simplificar | 78 | 0 | 30.251 s |
| `./mvnw -B -Dtest=ConcurrencyTest,WithdrawalTest,MoneyTest,FailureHandlingTest test` | 54 | 0 | 20.993 s |
| `./mvnw -B test` final | 77 | 0 | 34.137 s |
| `./mvnw -B test` confirmación completa | 77 | 0 | 28.368 s |

El conteo anterior de 78 es histórico, no el conteo final. La repetición de un retiro exitoso
se integró en la prueba del movimiento completo; se agruparon entradas y cargas repetidas
sin quitar escenarios únicos. Desglose final: ConcurrencyTest 8, WithdrawalTest 17,
TransferTest 22, FailureHandlingTest 18, MoneyTest 11 y ApplicationTest 1.

## Verificación de la organización por paquetes en Docker Desktop

Se usó Docker Desktop 29.8.2 (API 1.56, 3,558 MB de RAM), con Java y dependencias sin cambios.
Las bases temporales `/test` usan puertos aleatorios: no se modificó `financial` en 5433.

| Ejecución | Pruebas | Fallos / errores / omisiones | Salida | Tiempo Maven |
|---|---:|---|---:|---:|
| Base, antes de mover | 77 | 0 / 0 / 0 | 0 | 28.415 s |
| Paquetes reorganizados, compilación limpia | 77 | 0 / 0 / 0 | 0 | 26.438 s |
| Confirmación limpia final | 77 | 0 / 0 / 0 | 0 | 27.412 s |

Se conservaron las seis clases y los ocho casos concurrentes. Son verificaciones de
integridad, no una comparación de rendimiento entre motores.

### Docker Desktop en Linux

Si Testcontainers usa otro motor, seleccioná Desktop solo para esta ejecución:

```sh
DOCKER_HOST="$(docker context inspect desktop-linux --format '{{ .Endpoints.docker.Host }}')" \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./mvnw -B -Ddocker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy clean test
```

No modifica la configuración global de Docker ni los datos de Compose.

## Escenarios concurrentes verificados

Importes en soles. A/B inician en 10,000/0 salvo indicación. SUCCESS/REJECTED son operaciones
distintas persistidas; cada una tiene un intento coincidente. Todas las filas pasaron.

| Escenario (`ConcurrencyTest`) | Tareas × importe | SUCCESS / REJECTED | Saldo final A / B |
|---|---|---:|---|
| Retiros originales | 1,000 × 5 | 1,000 / 0 | 5,000 / 0 |
| Transferencias originales | 500 × 10 | 500 / 0 | 5,000 / 5,000 |
| Retiros adicionales | 10,000 × 1 | 10,000 / 0 | 0 / 0 |
| Transferencias adicionales | 5,000 × 1 | 5,000 / 0 | 5,000 / 5,000 |
| Sobredemanda de retiros; A=100 | 128 × 5 | 20 / 108 | 0 / 0 |
| Sobredemanda de transferencias; A=100 | 128 × 5 | 20 / 108 | 0 / 100 |
| Misma clave de retiro | 128 × 5 | 1 / 0 | 9,995 / 0 |
| Bidireccionales; A=B=10,000 | 512 × 10 | 512 / 0 | 10,000 / 10,000 |

## Cobertura y método

| Garantía | Pruebas |
|---|---|
| Inicio coordinado, sobredemanda, sumas y conciliación de todas las cuentas | ConcurrencyTest: seis cargas parametrizadas, misma clave y bidireccionales |
| Idempotencia HTTP en ambos endpoints, contenido distinto, rechazos estables | WithdrawalTest, TransferTest y FailureHandlingTest |
| Débito real seguido de fallo: rollback completo y auditoría FAILED | TransferTest y FailureHandlingTest |
| Máximo tres intentos, transacciones PostgreSQL distintas, bloqueo real 55P03 | FailureHandlingTest |
| Resultado incierto resuelto/no resuelto, auditoría concurrente y marca de interrupción | FailureHandlingTest |
| Centavos exactos, límites y validación HTTP sin cambios persistidos | MoneyTest, WithdrawalTest y TransferTest |

Las cargas llaman al orquestador público, con barrera de todas las tareas, hasta 64 llamadas
activas y 10 conexiones. Verifican respuestas contra SQL, saldos no negativos, estados,
intentos, sumas y [conciliación](transaction-flows.md#conciliar-desde-sql). Hay un plazo global
de 240 s, límite JUnit de 300 s y cierre de trabajadores acotado a 10 s.

Los fallos tras débito y pérdida del acuse de commit son inyectados, no cortes reales de red.
No es un benchmark: son ejecuciones únicas de integridad, no percentiles ni capacidad productiva.
No demuestra auditoría resistente a caídas, atomicidad distribuida ni seguridad bancaria.
Véanse la [nota técnica](technical-note.md) y las [instrucciones de ejecución](../README.md).
