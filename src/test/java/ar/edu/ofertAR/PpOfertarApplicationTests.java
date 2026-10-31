package ar.edu.ofertAR;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"jwt.secret=clave-de-test-solo-para-pruebas-0123456789abcdef",
		"ocr.password=test"
})
class PpOfertarApplicationTests {

	@Test
	void contextLoads() {
	}

}
