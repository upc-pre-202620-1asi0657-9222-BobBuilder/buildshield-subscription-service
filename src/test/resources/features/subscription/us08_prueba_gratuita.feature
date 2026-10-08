# language: es
Característica: US08 Prueba gratuita
  Como administrador de una empresa constructora
  quiero empezar con una prueba gratuita con todas las funciones
  para evaluar BuildShield antes de pagar

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" se registró en BuildShield

  Escenario: Al registrarse la organización empieza la prueba gratuita
    Dado que inicié sesión como "administrador" de la organización
    Cuando consulto la suscripción de mi organización
    Entonces la suscripción está en "En prueba gratuita" con el plan "Free" y le quedan 14 días
    Y la cuenta "no queda" en solo lectura
    Y se avisa a los demás servicios con el evento "SubscriptionActivated" en estado "TRIAL"

  Escenario: El registro repetido no abre una segunda prueba
    Dado que el evento de registro de la organización llegó dos veces
    Entonces la organización tiene una sola suscripción

  Escenario: Al terminar la prueba la cuenta queda en solo lectura
    Dado que inicié sesión como "encargado de obra" de la organización
    Y que pasaron 15 días
    Cuando se revisan los vencimientos
    Y consulto la suscripción de mi organización
    Entonces la suscripción está en "Vencida" con el plan "Free" y le quedan 0 días
    Y la cuenta "queda" en solo lectura
    Y se avisa a los demás servicios con el evento "SubscriptionExpired" en estado "EXPIRED"
