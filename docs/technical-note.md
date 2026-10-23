# Nota técnica

La solución protege los saldos con bloqueos de filas en PostgreSQL. Cada retiro bloquea su
cuenta; cada transferencia bloquea ambas. Así, dos operaciones sobre una misma cuenta no
pueden modificar su saldo al mismo tiempo.

## Concurrencia

Sin ese control, dos operaciones podrían leer un saldo de 100, restar 10 y guardar ambas 90.
Se perdería un débito. `@Transactional` permite confirmar o deshacer los cambios juntos, pero
no evita por sí sola esa lectura simultánea. `SELECT ... FOR UPDATE` hace esperar a la segunda
operación, que luego valida el saldo actualizado.

Primero se bloquea la operación y después las cuentas por ID ascendente, sin importar el
sentido de la transferencia. El orden evita ciclos entre cuentas que siguen este protocolo;
no elimina todos los posibles bloqueos mutuos o *deadlocks*. Una cuenta con muchas solicitudes
simultáneas obliga a esperar y ocupa conexiones mientras tanto.

## Transacciones e idempotencia

El registro inicial usa una transacción separada (`REQUIRES_NEW`). Guarda PROCESSING con una
clave única global, compartida por retiros y transferencias. Una repetición compara tipo,
origen, destino e importe: 5 y 5.00 representan lo mismo. La clave distingue mayúsculas y
espacios. Si los datos difieren, devuelve HTTP 409 sin alterar registros ni contadores.

Cada intento de procesamiento usa una nueva transacción. En una transferencia, débito,
crédito, resultado SUCCESS e intento se confirman juntos. Un rechazo guarda REJECTED y su
intento sin mover dinero. Un fallo entre débito y crédito revierte todo el procesamiento;
después, otra transacción registra el fallo sin sobrescribir resultados definitivos.

El dinero usa `BigDecimal` y `NUMERIC(19,2)`: importes positivos con céntimos exactos, sin
redondeo. La base impide saldos negativos; el límite del saldo destino se valida antes de
debitar. La [conciliación SQL](transaction-flows.md#conciliar-desde-sql) comprueba que cada saldo
sea el inicial menos salidas SUCCESS más entradas SUCCESS. Los intentos no son movimientos.

## Fallos y reintentos

Solo los errores 40P01 (deadlock), 40001 (conflicto de serialización) y 55P03 (espera de bloqueo
agotada), tras una reversión confirmada, permiten reintentos automáticos. Hay como máximo
tres intentos totales por llamada, con esperas de 20 y 40 ms fuera de las transacciones.
Al agotarlos queda FAILED reintentable; otros fallos de procesamiento quedan FAILED definitivo.
SUCCESS y REJECTED devuelven siempre el resultado guardado.

Si se pierde la conexión o la confirmación, el movimiento podría haberse aplicado. Otra
transacción bloquea y consulta la operación, comprueba sus datos y devuelve el resultado
definitivo o HTTP 503 con OUTCOME_UNKNOWN. No reintenta a ciegas: el cliente debe conservar
la misma clave. Una caída o una base no disponible puede impedir la auditoría del fallo y
dejar PROCESSING. No hay recuperación automática en segundo plano.

## Alternativas y límites

El control optimista con `@Version` detecta cambios simultáneos, pero aumenta los reintentos
cuando muchas solicitudes compiten. Una actualización condicional simplifica el débito,
aunque crédito y registro aún necesitan una transacción. El aislamiento serializable también
puede exigir reintentos.

Una transacción local no cubre cuentas en bases distintas. La confirmación en dos fases
(2PC) añade coordinación y costes de disponibilidad; una saga compensa pasos ya confirmados
y admite estados intermedios. Ninguna está implementada.

Como mejoras, se proponen métricas de esperas, deadlocks y reintentos; logs de fallos sin datos
sensibles; y alertas por resultados inciertos, fallos de auditoría o diferencias de conciliación.
Estas medidas no están implementadas. Los [resultados](test-results.md) detallan el alcance de las pruebas.
