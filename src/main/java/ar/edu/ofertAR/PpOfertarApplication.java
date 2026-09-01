package ar.edu.ofertAR;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PpOfertarApplication {

	public static void main(String[] args) {
		SpringApplication.run(PpOfertarApplication.class, args);
	}

}
