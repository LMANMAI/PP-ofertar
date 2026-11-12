package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.AddHabitualProductRequest;
import ar.edu.ofertAR.model.HabitualProduct;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.HabitualProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HabitualProductServiceTest {

    private static final String EAN = "7790895000997";
    /** El mismo producto, en el formato de 14 dígitos que mezcla SEPA. */
    private static final String EAN_14 = "07790895000997";
    private static final String OTHER_EAN = "7791234567891";
    private static final String DESC = "Gaseosa Coca Cola 2.25 L";

    @Mock private HabitualProductRepository repository;

    private HabitualProductService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new HabitualProductService(repository);
        user = new User();
        user.setId(1L);
    }

    private static HabitualProduct product(String ean, String description) {
        return HabitualProduct.builder().ean(ean).description(description).build();
    }

    private static TicketItem line(String barcode, String description) {
        TicketItem line = new TicketItem();
        line.setBarcode(barcode);
        line.setDescription(description);
        return line;
    }

    private void has(HabitualProduct... products) {
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(List.of(products));
    }

    @Test
    @DisplayName("el EAN de la línea se compara normalizado")
    void sameEanAcrossFormats() {
        assertTrue(HabitualProductService.isSameProduct(product(EAN, DESC), line(EAN_14, "GASEOSA")));
    }

    @Test
    @DisplayName("una línea con otro EAN no es el producto, aunque se llame igual")
    void differentEan() {
        assertFalse(HabitualProductService.isSameProduct(product(EAN, DESC), line(OTHER_EAN, DESC)));
    }

    @Test
    @DisplayName("una línea sin código se compara por nombre")
    void noBarcodeSameName() {
        assertTrue(HabitualProductService.isSameProduct(product(EAN, DESC), line(null, "GASEOSA COCA COLA 2.25 L")));
    }

    @Test
    @DisplayName("una línea sin código y de otro producto no cuenta")
    void noBarcodeOtherName() {
        assertFalse(HabitualProductService.isSameProduct(product(EAN, DESC), line("", "YERBA MATE 1 KG")));
    }

    @Test
    @DisplayName("una línea sin código ni nombre no cuenta")
    void emptyLine() {
        assertFalse(HabitualProductService.isSameProduct(product(EAN, DESC), line(null, " ")));
    }

    @Test
    @DisplayName("sumar guarda el producto sin espacios de más y sin marca vacía")
    void addSaves() {
        has();
        service.add(user, new AddHabitualProductRequest(EAN, "  " + DESC + " ", "  "));

        ArgumentCaptor<HabitualProduct> saved = ArgumentCaptor.forClass(HabitualProduct.class);
        verify(repository).save(saved.capture());
        assertEquals(DESC, saved.getValue().getDescription());
        assertNull(saved.getValue().getBrand());
        assertSame(user, saved.getValue().getUser());
    }

    @Test
    @DisplayName("sumar uno que ya está, en otro formato, no agrega otra fila")
    void addIsIdempotentAcrossFormats() {
        has(product(EAN, DESC));
        service.add(user, new AddHabitualProductRequest(EAN_14, DESC, null));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("el doble toque que frena la clave única no es un error")
    void addSurvivesARace() {
        has();
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk"));
        assertDoesNotThrow(() -> service.add(user, new AddHabitualProductRequest(EAN, DESC, null)));
    }

    @Test
    @DisplayName("en el tope avisa, pero uno que ya está no cuenta como nuevo")
    void addRespectsTheCap() {
        List<HabitualProduct> full = IntStream.range(0, HabitualProductService.MAX_PRODUCTS)
                .mapToObj(i -> product("779" + String.format("%010d", i), "Producto " + i))
                .toList();
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(full);

        assertThrows(IllegalArgumentException.class,
                () -> service.add(user, new AddHabitualProductRequest(EAN, DESC, null)));
        assertDoesNotThrow(() -> service.add(user, new AddHabitualProductRequest(full.get(0).getEan(), "x", null)));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("sacar funciona con el EAN en otro formato y no toca los demás")
    void removeByNormalizedEan() {
        HabitualProduct target = product(EAN, DESC);
        has(target, product(OTHER_EAN, "Otro"));
        service.remove(user, EAN_14);
        verify(repository).deleteAll(List.of(target));
    }

    @Test
    @DisplayName("sacar uno que no está no borra nada")
    void removeMissing() {
        has(product(OTHER_EAN, "Otro"));
        service.remove(user, EAN);
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("listEans devuelve los códigos tal como están guardados")
    void listEans() {
        has(product(EAN_14, DESC));
        assertEquals(List.of(EAN_14), service.listEans(user));
    }
}
