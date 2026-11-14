package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.AddHabitualProductRequest;
import ar.edu.ofertAR.model.HabitualProduct;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.HabitualProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lo que el usuario sumó a mano a su compra habitual. Leerla es
 * {@link ProductService#getRecurringProducts}, que lo junta con lo detectado en
 * los tickets; acá solo se suma, se saca y se responde qué códigos hay.
 */
@Service
@RequiredArgsConstructor
public class HabitualProductService {

    /** Cada producto es una línea más en la consulta de ofertas de cada carga de la compra habitual. */
    static final int MAX_PRODUCTS = 50;

    private final HabitualProductRepository repository;

    public List<String> listEans(User user) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .map(HabitualProduct::getEan)
                .toList();
    }

    /** Sumar uno que ya está no es un error, aunque venga en otro formato de EAN. */
    public void add(User user, AddHabitualProductRequest request) {
        String ean = request.getEan().trim();
        List<HabitualProduct> current = repository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
        if (current.stream().anyMatch(p -> ProductKeys.sameEan(p.getEan(), ean))) {
            return;
        }
        if (current.size() >= MAX_PRODUCTS) {
            throw new IllegalArgumentException(
                    "Sumaste " + MAX_PRODUCTS + " productos a tu compra habitual. Sacá alguno para sumar otro.");
        }
        try {
            repository.save(HabitualProduct.builder()
                    .user(user)
                    .ean(ean)
                    .description(request.getDescription().trim())
                    .brand(request.getBrand() == null || request.getBrand().isBlank() ? null : request.getBrand().trim())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Dos toques al botón: la clave única frenó el segundo y el producto ya quedó sumado.
        }
    }

    /** Idempotente, y por EAN normalizado: el código puede venir en otro formato que el guardado. */
    public void remove(User user, String ean) {
        List<HabitualProduct> same = repository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .filter(p -> ProductKeys.sameEan(p.getEan(), ean))
                .toList();
        if (!same.isEmpty()) {
            repository.deleteAll(same);
        }
    }

    /**
     * Si una línea de ticket es este producto. Con EAN válido se compara solo
     * por EAN: el código no se equivoca de variedad. Sin código, el nombre es lo
     * único que hay, y ahí conviene darlo por comprado antes que avisar de un
     * olvido que no pasó.
     */
    static boolean isSameProduct(HabitualProduct product, TicketItem line) {
        if (ProductKeys.eanKey(line.getBarcode()) != null) {
            return ProductKeys.sameEan(product.getEan(), line.getBarcode());
        }
        String name = line.getDescription();
        return name != null && !name.isBlank() && !ProductKeys.isLooseMatch(product.getDescription(), name);
    }
}
