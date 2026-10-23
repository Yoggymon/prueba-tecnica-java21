# Nota técnica: consistencia financiera local

La solución usa bloqueo pesimista en PostgreSQL: una decisión simple para cuentas disputadas
en una sola base. Véanse los [flujos](transaction-flows.md) y [resultados](test-results.md).
Extensión prevista: 1–2 páginas; paginación PDF no verificada.

## Concurrencia y alternativas

Con saldo 100, dos transacciones pueden leer 100, restar 10 y escribir ambas 90: se pierde
un débito. `@Transactional` aporta atomicidad, pero no serializa por sí sola esa secuencia
con el aislamiento predeterminado. `SELECT ... FOR UPDATE` obliga a esperar; la siguiente
operación valida el saldo actualizado bajo bloqueo.

Se bloquea primero la operación y después las cuentas por ID ascendente, independientemente
del sentido de transferencia. Este orden evita ciclos entre cuentas dentro del protocolo,
no cualquier deadlock ni los causados por escritores externos. El coste es serializar
una cuenta muy disputada y mantener conexiones ocupadas durante las esperas.

Alternativas: `@Version` detecta escrituras obsoletas, pero exige más reintentos bajo contención;
un `UPDATE ... WHERE balance >= amount` resuelve el débito, aunque crédito, auditoría e
idempotencia aún necesitan una transacción. Serializable también detecta conflictos y exige
reintentos. No hace falta combinar estas estrategias para cumplir el ejercicio.

## Transacciones e idempotencia

El registro `REQUIRES_NEW` guarda PROCESSING con clave global única o compara el contenido
normalizado existente: tipo, origen, destino e importe. 5 y 5.00 coinciden; claves con
mayúsculas o espacios distintos no se normalizan. Un contenido distinto devuelve 409 sin
cambiar datos ni contadores.

El procesamiento llama a otro componente `@Transactional`: bloquea operación y cuentas,
valida y confirma saldos, SUCCESS e intento juntos. Un rechazo confirma REJECTED e intento,
sin mover dinero. Un fallo entre débito y crédito revierte todas las escrituras del procesamiento.
Solo SUCCESS representa un movimiento financiero; `operation_attempts` registra intentos.

El dinero usa `BigDecimal` y `NUMERIC(19,2)`, nunca float/double. Se exigen centavos positivos
exactos, sin redondear. SQL impide saldos negativos; superar el límite del destino es un
rechazo previo al débito. Por cuenta se verifica desde una instantánea SQL consistente:

```text
balance = initial_balance − SUM(importes de origen SUCCESS)
                          + SUM(importes de destino SUCCESS TRANSFER)
```

Los rechazos y fallos no entran en esa suma. Las transferencias conservan el total; los retiros
exitosos lo reducen. La [consulta de conciliación](transaction-flows.md#conciliar-desde-sql)
permite comprobarlo sin depender de respuestas HTTP. No hay claves foráneas hacia cuentas
en las operaciones: así se conserva ACCOUNT_NOT_FOUND como rechazo auditable.

## Fallos y recuperación

Tras una reversión confirmada, otra transacción registra el fallo sin sobrescribir resultados
terminales concurrentes. Solo SQLSTATE 40P01, 40001 y 55P03 permiten reintentos automáticos:
tres intentos totales por llamada, en transacciones nuevas, con esperas de 20/40 ms fuera de
transacciones. El agotamiento deja FAILED reintentable con la misma clave; un fallo genérico
deja FAILED terminal. SUCCESS y REJECTED siempre se repiten.

Un fallo de conexión o confirmación puede ocultar un commit exitoso. Se bloquea y consulta
el estado persistido en otra transacción, verificando el contenido. Se devuelve el resultado
terminal o 503 OUTCOME_UNKNOWN, nunca un FAILED inventado ni un reintento a ciegas.
El cliente conserva la clave. Una caída puede perder la auditoría separada de fallos y dejar
PROCESSING; no existe recuperación en segundo plano ni auditoría resistente a caídas.

## Límites y mejoras operativas

Una transacción local no cubre bases distintas. Dos fases coordinaría un commit distribuido
con costes de disponibilidad y operación; una saga usa compensaciones y expone estados
intermedios, sin la misma atomicidad. Ninguna está implementada.

Como mejora opcional: medir esperas, deadlocks, reintentos agotados, resultados inciertos,
fallos de auditoría y discrepancias de conciliación; alertar sobre los últimos tres.
Usar correlación depurada, nunca claves originales ni datos sensibles. Otra mejora sería
ensayar cortes reales de conexión: hoy solo el bloqueo 55P03 es real; la pérdida del acuse
se inyecta en pruebas. No hay benchmark, certificación bancaria ni endurecimiento productivo;
Java 21.0.2 y las dependencias fijadas requieren revisar parches antes de un despliegue real.
