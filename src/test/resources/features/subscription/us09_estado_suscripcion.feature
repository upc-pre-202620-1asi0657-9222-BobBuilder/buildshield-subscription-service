# language: es
Característica: US09 Consulta del estado de la suscripción y de los planes
  Como usuario de una empresa constructora
  quiero ver el estado de mi suscripción y los planes disponibles
  para saber hasta cuándo puedo usar BuildShield y cuánto cuesta continuar

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" se registró en BuildShield

  Escenario: Cualquier rol consulta los días que le quedan
    Dado que inicié sesión como "encargado de almacén" de la organización
    Y que pasaron 4 días
    Cuando consulto la suscripción de mi organización
    Entonces la suscripción está en "En prueba gratuita" con el plan "Free" y le quedan 10 días

  Escenario: Planes por número de obras activas, mensual o anual con descuento
    Dado que inicié sesión como "administrador" de la organización
    Cuando consulto los planes
    Entonces veo los planes "Free, Plus, Pro" en ese orden
    Y el plan "Free" es solo de prueba por 14 días
    Y el plan "Plus" permite "5" obras activas
    Y el plan "Pro" permite "ilimitadas" obras activas
    Y el plan "Plus" cuesta "299.00" al mes y "2990.00" al año
    Y el plan "Pro" cuesta "699.00" al mes y "6990.00" al año

  Escenario: Los planes se ven sin iniciar sesión, con el ahorro del pago anual
    Cuando consulto los planes sin iniciar sesión
    Entonces veo los planes "Free, Plus, Pro" en ese orden
    Y el plan "Plus" ahorra "598.00" al año, un "16.67" % frente a 12 pagos mensuales
    Y el plan "Pro" ahorra "1398.00" al año, un "16.67" % frente a 12 pagos mensuales

  Escenario: Sin sesión no se consulta la suscripción
    Cuando consulto la suscripción de mi organización
    Entonces la respuesta tiene estado 401
