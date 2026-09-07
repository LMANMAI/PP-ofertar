package ar.edu.ofertAR;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class PpOfertarApplication {

	/**
	 * Every date this app reasons about is an Argentine one — the month a
	 * ticket belongs to, the day a promotion expires — but the runtime image
	 * (eclipse-temurin, no TZ set) defaults the JVM to UTC. Left alone,
	 * LocalDateTime.now() stamps a ticket scanned at 21:30 on the 31st as
	 * 00:30 on the 1st, moving it into the next month. Set before the context
	 * starts so nothing reads a clock in the wrong zone first.
	 */
	public static final TimeZone ARGENTINA = TimeZone.getTimeZone("America/Argentina/Buenos_Aires");

	public static void main(String[] args) {
		TimeZone.setDefault(ARGENTINA);
		SpringApplication.run(PpOfertarApplication.class, args);
	}

}
