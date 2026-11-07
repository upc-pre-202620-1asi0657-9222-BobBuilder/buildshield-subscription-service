# language: es
Característica: US07 y US09 Elegir el plan y pagarlo con Stripe Checkout
  Como administrador de una empresa constructora
  quiero elegir el plan Plus o Pro, mensual o anual, y pagarlo en la pasarela
  para seguir usando BuildShield sin que la empresa entregue datos de tarjeta a BuildShield

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" se registró en BuildShield
    Y que inicié sesión como "administrador" de la organización

  Escenario: El pago confirmado por un webhook firmado activa el plan Plus anual
    Cuando pido pagar el plan "PLUS" en modalidad "ANNUAL"
    Entonces recibo una sesión de pago por "2990.00" lista para redirigir
    Cuando la pasarela confirma el pago de la sesión con un webhook firmado
    Entonces el webhook responde "PROCESSED"
    Cuando consulto la suscripción de mi organización
    Entonces la suscripción está activa con el plan "Plus" por 12 meses
    Y la modalidad es "Anual" con la próxima fecha de cobro al fin del periodo
    Y la cuenta "no queda" en solo lectura
    Y se avisa a los demás servicios con el evento "SubscriptionActivated" en estado "ACTIVE"

  Escenario: El mismo webhook repetido se aplica una sola vez
    Dado que pedí pagar el plan "PRO" en modalidad "MONTHLY"
    Cuando la pasarela confirma el pago de la sesión con un webhook firmado
    Y la pasarela reenvía el mismo webhook
    Entonces el webhook responde "DUPLICATE"
    Y la suscripción se activó una sola vez

  Escenario: Un webhook sin firma válida se rechaza
    Dado que pedí pagar el plan "PRO" en modalidad "MONTHLY"
    Cuando llega un webhook de pago con una firma falsa
    Entonces la operación es rechazada con estado 400 y código "INVALID_WEBHOOK_SIGNATURE"
    Y la suscripción sigue en "En prueba gratuita"

  Escenario: En desarrollo local el pago simulado activa el plan elegido
    Dado que pedí pagar el plan "PLUS" en modalidad "MONTHLY"
    Cuando completo el pago simulado de la sesión
    Entonces la respuesta tiene estado 200
    Y la suscripción está activa con el plan "Plus" por 1 meses

  Escenario: Pedir de nuevo el mismo plan reutiliza la sesión abierta
    Dado que pedí pagar el plan "PLUS" en modalidad "ANNUAL"
    Cuando pido pagar el plan "PLUS" en modalidad "ANNUAL"
    Entonces la sesión de pago es la misma de antes

  Escenario: El plan Free no se paga
    Cuando pido pagar el plan "FREE" en modalidad "MONTHLY"
    Entonces la operación es rechazada con estado 400 y código "PLAN_NOT_PAYABLE"

  Escenario: Solo el administrador paga la suscripción
    Dado que inicié sesión como "encargado de almacén" de la organización
    Cuando pido pagar el plan "PLUS" en modalidad "MONTHLY"
    Entonces la operación es rechazada con estado 403

  Escenario: Con la prueba vencida la cuenta sale de solo lectura al pagar
    Dado que pasaron 15 días
    Y se revisan los vencimientos
    Y que pedí pagar el plan "PRO" en modalidad "MONTHLY"
    Cuando completo el pago simulado de la sesión
    Y consulto la suscripción de mi organización
    Entonces la suscripción está activa con el plan "Pro" por 1 meses
    Y la cuenta "no queda" en solo lectura
