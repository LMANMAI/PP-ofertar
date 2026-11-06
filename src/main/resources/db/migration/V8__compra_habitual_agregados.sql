-- Productos que el usuario sumó a su compra habitual desde el escáner, aunque todavía no salgan de
-- sus tickets. Se suman a los que se detectan solos y, como ellos, avisan si faltan en un ticket.
-- La clave única (user_id, ean) también sirve de índice para la FK y para listar por usuario.
CREATE TABLE `habitual_products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `ean` varchar(20) NOT NULL,
  `description` varchar(300) NOT NULL,
  `brand` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_habitual_products_user_ean` (`user_id`,`ean`),
  CONSTRAINT `fk_habitual_products_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
