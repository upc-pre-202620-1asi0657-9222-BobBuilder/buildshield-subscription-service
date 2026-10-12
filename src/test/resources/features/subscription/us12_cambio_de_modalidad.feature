# language: es
Característica: US12 Cambiar entre mensual y anual
  Como administrador de una empresa constructora
  quiero cambiar la modalidad de pago entre mensual y anual
  para pagar como más le conviene a la empresa desde el siguiente cobro

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" se registró en BuildShield
    Y que inicié sesión como "administrador" de la organización
    Y que la organización pagó el plan "PLUS" en modalidad "MONTHLY"

  Escenario: El cambio a anual se programa para el siguiente periodo de cobro
    Cuando cambio la modalidad a "ANNUAL"
    Entonces el cambio queda "SCHEDULED" para la próxima fecha de cobro
    Y la modalidad es "Mensual" con la próxima fecha de cobro al fin del periodo
    Y se avisa a los demás servicios con el evento "SubscriptionBillingPeriodChangeScheduled" en estado "ACTIVE"

  Escenario: Al empezar el siguiente periodo rige la nueva modalidad
    Dado que cambié la modalidad a "ANNUAL"
    Y que pasaron 32 días
    Cuando se revisan los vencimientos
    Y consulto la suscripción de mi organización
    Entonces la modalidad es "Anual" con la próxima fecha de cobro al fin del periodo
    Y no hay cambio de modalidad programado

  Escenario: Volver a la modalidad actual cancela el cambio programado
    Dado que cambié la modalidad a "ANNUAL"
    Cuando cambio la modalidad a "MONTHLY"
    Entonces el cambio queda "CANCELLED" para la próxima fecha de cobro
    Y no hay cambio de modalidad programado

  Escenario: Solo el administrador cambia la modalidad
    Dado que inicié sesión como "encargado de obra" de la organización
    Cuando cambio la modalidad a "ANNUAL"
    Entonces la operación es rechazada con estado 403
