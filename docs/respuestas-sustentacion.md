
## P1. ¿Qué condición de carrera ocurriría si dos transacciones leen el mismo saldo y luego lo actualizan?

Puede ocurrir un problema conocido como **actualización perdida (Lost Update)**.

Por ejemplo, si una cuenta tiene S/ 100 y se realizan dos retiros de S/ 10 al mismo tiempo, ambas operaciones podrían leer el saldo inicial y actualizarlo a S/ 90.

El problema es que se realizaron dos retiros, pero el saldo solo refleja uno. Lo correcto sería que queden S/ 80.

Por eso, es importante controlar el acceso al saldo para evitar que varias operaciones lo modifiquen al mismo tiempo sin considerar los cambios anteriores.

## P2. ¿Por qué @Transactional por sí solo no garantiza que el saldo sea correcto?

Porque `@Transactional` permite que los cambios de una operación se confirmen o se reviertan si ocurre algún error, pero no impide que varias transacciones consulten el mismo saldo simultáneamente.

Por ejemplo, dos retiros podrían leer el mismo saldo antes de que alguno lo actualice.

Por ese motivo, además de `@Transactional`, utilizo **`SELECT ... FOR UPDATE`**, que permite bloquear temporalmente el registro de la cuenta mientras se realiza la operación.

De esta manera, se evita que otra operación tome decisiones utilizando un saldo desactualizado.

## P3. ¿Qué estrategia eligió y qué ocurre cuando dos operaciones compiten por la misma cuenta?

Elegí utilizar **bloqueo pesimista**, mediante `SELECT ... FOR UPDATE` en PostgreSQL.

Este mecanismo permite que, cuando una transacción está trabajando con una cuenta bloqueada, otra que necesita bloquear esa misma cuenta tenga que esperar.

Una vez que termina la primera operación, la segunda puede continuar utilizando el saldo actualizado.

En el caso de las transferencias, las cuentas se bloquean siguiendo un orden ascendente por su ID. Esto permite reducir el riesgo de bloqueos mutuos, también conocidos como **deadlocks**, que ocurren cuando dos transacciones quedan esperando recursos bloqueados entre sí.

Considero que esta estrategia es adecuada porque permite controlar directamente las operaciones que comparten una misma cuenta.

## P4. ¿Cómo evita que un reintento del cliente duplique un movimiento?

Para evitar movimientos duplicados, utilizo una **clave de idempotencia**, que funciona como un identificador único para cada solicitud.

Esta clave se almacena en PostgreSQL y tiene una restricción `UNIQUE`, que impide registrar dos operaciones con el mismo identificador.

Por ejemplo, si un cliente realiza un retiro y, debido a un problema de conexión, vuelve a enviar la misma solicitud, el sistema reconoce la clave y evita descontar nuevamente el dinero.

Si la operación ya fue procesada, devuelve el resultado registrado. Si se utiliza la misma clave con datos diferentes, responde con un error **HTTP 409 (Conflicto)**.

Además, se controla que dos solicitudes con la misma clave no ejecuten el movimiento simultáneamente.

Si ocurre un error de conexión y no es posible confirmar el resultado, el sistema puede devolver **HTTP 503 con `OUTCOME_UNKNOWN`**. En ese caso, el cliente debe consultar o reintentar utilizando la misma clave.

De esta manera, se evita que los reintentos generen retiros o transferencias duplicadas.

## P5. ¿Cómo garantizaría la atomicidad si las cuentas estuvieran en bases de datos distintas?

Cuando las cuentas están en bases de datos diferentes, una transacción local no es suficiente para garantizar que todos los cambios se realicen juntos.

En ese escenario, evaluaría dos alternativas:

- **Transacciones distribuidas (2PC):** permiten coordinar las bases de datos para que los cambios se confirmen o se reviertan como una sola operación. Sin embargo, requieren mayor complejidad.

- **Patrón Saga:** permite dividir una operación en varios pasos y aplicar acciones de compensación cuando ocurre algún error. A diferencia de 2PC, no garantiza atomicidad inmediata y puede presentar estados intermedios.

Mi primera recomendación sería mantener las operaciones dentro de una misma base de datos, siempre que la arquitectura del sistema lo permita.

Si fuera necesario trabajar con varias bases, elegiría la alternativa según los requisitos del negocio, considerando que una Saga ofrece consistencia eventual, pero no las mismas garantías de atomicidad que 2PC.

## P6. ¿Qué métricas, logs y alertas agregaría para diagnosticar contención, deadlocks y fallos de transacción?

Como mejoras adicionales, implementaría un sistema básico de monitoreo para identificar problemas durante las operaciones.

Principalmente, consideraría:

- **Métricas:** mediría los tiempos de respuesta, el tiempo de espera por bloqueos, la cantidad de deadlocks, los reintentos fallidos, las operaciones con resultado incierto y las conexiones disponibles.

- **Logs:** registraría información importante de cada operación, como su identificador, el resultado obtenido y los detalles del error cuando corresponda. También evitaría almacenar información sensible de los usuarios.

- **Alertas:** configuraría notificaciones cuando existan demasiados errores, tiempos de espera elevados, operaciones pendientes durante mucho tiempo, problemas en los registros de auditoría o diferencias entre los saldos y los movimientos realizados.

Estas mejoras permitirían detectar problemas con mayor facilidad, identificar posibles fallos de concurrencia y mantener un mejor control del sistema.

Actualmente, considero estas herramientas de monitoreo como **recomendaciones para una implementación futura**, no como funcionalidades ya desarrolladas.

