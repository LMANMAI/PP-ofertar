-- Mis tarjetas (OFT-51): los medios de pago que el usuario declara para cruzarlos con las
-- promos bancarias. NUNCA se guarda el número de tarjeta, el vencimiento ni el CVV: sólo
-- el emisor (slug de payment_issuers, la tabla que escribe el scraper), la red y el tipo.
-- issuer_slug no lleva FK a payment_issuers porque esa tabla es del scraper y no la crea
-- Flyway; el backend valida el slug contra el catálogo al guardar.
-- Ojo: en MySQL la clave única no frena dos filas con NULL en network o card_type (NULL
-- no es igual a NULL), así que el servicio deduplica antes de guardar.
CREATE TABLE `user_payment_methods` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `issuer_slug` varchar(60) NOT NULL,
  `network` varchar(20) DEFAULT NULL,
  `card_type` varchar(20) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_payment_methods_user_method` (`user_id`,`issuer_slug`,`network`,`card_type`),
  CONSTRAINT `fk_user_payment_methods_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Beneficios que el usuario declara (jubilado, plan sueldo, Club La Nación...). Las promos
-- con esas condiciones sólo le matchean si las declaró (D6).
CREATE TABLE `user_benefits` (
  `user_id` bigint NOT NULL,
  `benefit` varchar(40) NOT NULL,
  PRIMARY KEY (`user_id`,`benefit`),
  CONSTRAINT `fk_user_benefits_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
