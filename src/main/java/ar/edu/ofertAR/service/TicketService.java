package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.UpdateTicketRequest;
import ar.edu.ofertAR.dto.response.TicketItemResponse;
import ar.edu.ofertAR.dto.response.TicketResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import ar.edu.ofertAR.service.ocr.OcrClient;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrItem;
import ar.edu.ofertAR.service.ocr.OcrClient.OcrResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final OcrClient ocrClient;
    private final ExecutorService ocrExecutor;

    @Value("${ticket.upload-dir:uploads/tickets}")
    private String uploadDir;

    // Was a hardcoded constant (3) — raised and made configurable so "productos
    // recurrentes" has a meaningful purchase-history window to work with.
    @Value("${ticket.max-per-user:50}")
    private int maxTicketsPerUser;

    @Transactional
    public TicketResponse scan(List<MultipartFile> files, User user) {
        for (MultipartFile file : files) {
            validateImage(file);
        }

        enforceTicketLimit(user);

        List<String> savedPaths = new ArrayList<>();
        for (MultipartFile file : files) {
            savedPaths.add(saveImage(file));
        }
        String imagePath = String.join(",", savedPaths);

        Ticket ticket = Ticket.builder()
                .user(user)
                .imagePath(imagePath)
                .status(TicketStatus.PENDING)
                .build();

        ticket = ticketRepository.save(ticket);
        log.info("Ticket {} creado con {} archivos", ticket.getId(), files.size());

        // Read every payload up front: MultipartFile is tied to the request
        // thread, so the bytes have to be pulled before handing pages off to
        // the OCR pool.
        List<byte[]> payloads = new ArrayList<>();
        List<String> contentTypes = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                payloads.add(file.getBytes());
                contentTypes.add(file.getContentType());
            } catch (IOException e) {
                log.error("Error al leer archivo del ticket {}: {}", ticket.getId(), e.getMessage());
                ticket.setStatus(TicketStatus.FAILED);
                ticketRepository.save(ticket);
                return toResponse(ticket);
            }
        }

        // Pages are independent OCR calls, so run them concurrently: a 5-page
        // ticket now costs the slowest page instead of the sum of all of them.
        List<CompletableFuture<OcrResult>> futures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            final byte[] bytes = payloads.get(i);
            final String contentType = contentTypes.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> ocrClient.processTicket(bytes, contentType), ocrExecutor));
        }

        List<OcrResult> pageResults = new ArrayList<>();
        try {
            // join() keeps the original page order, which matters because the
            // item list is shown to the user in the order it was printed.
            for (CompletableFuture<OcrResult> future : futures) {
                pageResults.add(future.join());
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Fallo OCR en el ticket {}: {}", ticket.getId(), cause.getMessage());
            ticket.setStatus(TicketStatus.FAILED);
            ticketRepository.save(ticket);
            return toResponse(ticket);
        }

        boolean anyProcessed = false;
        String mergedStoreName = null;
        String mergedTicketId = null;
        BigDecimal mergedTotal = BigDecimal.ZERO;
        BigDecimal mergedSubtotal = BigDecimal.ZERO;
        BigDecimal mergedTotalDiscounts = BigDecimal.ZERO;
        List<TicketItem> allItems = new ArrayList<>();

        for (int i = 0; i < pageResults.size(); i++) {
            OcrResult ocrResult = pageResults.get(i);

            if (mergedStoreName == null || mergedStoreName.isEmpty()) {
                mergedStoreName = ocrResult.supermarketName();
            }
            if (mergedTicketId == null || mergedTicketId.isEmpty()) {
                mergedTicketId = ocrResult.ticketId();
            }
            mergedTotal = mergedTotal.max(ocrResult.total());
            mergedSubtotal = mergedSubtotal.max(ocrResult.subtotal());
            mergedTotalDiscounts = mergedTotalDiscounts.max(ocrResult.totalDiscounts());

            for (OcrItem ocrItem : ocrResult.items()) {
                TicketItem item = TicketItem.builder()
                        .ticket(ticket)
                        .description(ocrItem.description())
                        .rawDescription(ocrItem.rawDescription())
                        .quantity(ocrItem.quantity())
                        .unitPrice(ocrItem.price().divide(
                                BigDecimal.valueOf(ocrItem.quantity()), 2, RoundingMode.HALF_UP))
                        .originalPrice(ocrItem.originalPrice())
                        .subtotal(ocrItem.price())
                        .barcode(ocrItem.code())
                        .category(ocrItem.category())
                        .discountAmount(ocrItem.discountAmount())
                        .discountDescription(ocrItem.discountDescription())
                        .build();
                allItems.add(item);
            }
            anyProcessed = true;
            log.info("Pagina {}/{} del ticket {} procesada — {} items",
                    i + 1, pageResults.size(), ticket.getId(), ocrResult.items().size());
        }

        if (mergedTicketId != null && !mergedTicketId.isEmpty()) {
            Optional<Ticket> existing = ticketRepository.findByUserIdAndTicketIdAndStatus(
                    user.getId(), mergedTicketId, TicketStatus.PROCESSED);
            if (existing.isPresent()) {
                log.info("Ticket {} ya existe (ticketId={}), descartando PENDING {}", 
                        existing.get().getId(), mergedTicketId, ticket.getId());
                deleteImageFile(ticket.getImagePath());
                ticketRepository.delete(ticket);
                return toResponse(existing.get());
            }
        }

        // The per-page totals come from arithmetic the model did on its own,
        // and measurements showed that to be the least reliable field it
        // returns (observed: 0.0, a stray discount amount, and a value 80
        // pesos off while its own line items summed exactly right). The items
        // are individually readable off the receipt, so derive the money from
        // them and only fall back to the model's figure when nothing parsed.
        if (!allItems.isEmpty()) {
            BigDecimal itemsTotal = allItems.stream()
                    .map(it -> it.getSubtotal() != null ? it.getSubtotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal itemsDiscounts = allItems.stream()
                    .map(it -> it.getDiscountAmount() != null ? it.getDiscountAmount().abs() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (mergedTotal.compareTo(itemsTotal) != 0) {
                log.info("Ticket {}: total del modelo {} reemplazado por la suma de items {}",
                        ticket.getId(), mergedTotal, itemsTotal);
            }
            mergedTotal = itemsTotal;
            mergedTotalDiscounts = itemsDiscounts;
            // Subtotal is the pre-discount figure, i.e. what was paid plus
            // everything that was taken off.
            mergedSubtotal = itemsTotal.add(itemsDiscounts);
        }

        ticket.setStoreName(mergedStoreName);
        ticket.setTicketId(mergedTicketId);
        ticket.setTotal(mergedTotal);
        ticket.setSubtotal(mergedSubtotal);
        ticket.setTotalDiscounts(mergedTotalDiscounts);
        ticket.setStatus(anyProcessed ? TicketStatus.PROCESSED : TicketStatus.FAILED);
        ticket.setItems(allItems);

        ticket = ticketRepository.save(ticket);
        log.info("Ticket {} procesado exitosamente — {} items de {} paginas, super: {}",
                ticket.getId(), allItems.size(), files.size(), ticket.getStoreName());

        return toResponse(ticket);
    }

    public List<TicketResponse> getTicketsByUser(User user) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TicketResponse getTicketById(Long id, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));
        return toResponse(ticket);
    }

    @Transactional
    public TicketResponse updateTicket(Long id, UpdateTicketRequest request, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));

        if (request.getStoreName() != null) {
            ticket.setStoreName(request.getStoreName());
        }

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            Set<Long> requestedIds = request.getItems().stream()
                    .map(UpdateTicketRequest.TicketItemUpdate::getId)
                    .filter(rid -> rid != null)
                    .collect(Collectors.toSet());

            Iterator<TicketItem> iterator = ticket.getItems().iterator();
            while (iterator.hasNext()) {
                TicketItem existing = iterator.next();
                if (!requestedIds.contains(existing.getId())) {
                    iterator.remove();
                }
            }

            BigDecimal newSubtotal = BigDecimal.ZERO;
            for (UpdateTicketRequest.TicketItemUpdate reqItem : request.getItems()) {
                TicketItem item;
                if (reqItem.getId() != null) {
                    item = ticket.getItems().stream()
                            .filter(i -> i.getId().equals(reqItem.getId()))
                            .findFirst()
                            .orElse(null);
                    if (item != null) {
                        if (reqItem.getDescription() != null) {
                            item.setDescription(reqItem.getDescription());
                        }
                        if (reqItem.getQuantity() != null) {
                            item.setQuantity(reqItem.getQuantity());
                        }
						if (reqItem.getUnitPrice() != null) {
							item.setUnitPrice(reqItem.getUnitPrice());
						}
						if (reqItem.getOriginalPrice() != null) {
							item.setOriginalPrice(reqItem.getOriginalPrice());
						}
						if (reqItem.getDiscountAmount() != null) {
							item.setDiscountAmount(reqItem.getDiscountAmount());
						}
                        item.setSubtotal(
                                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                        );
                        newSubtotal = newSubtotal.add(item.getSubtotal());
                        continue;
                    }
                }

                int qty = reqItem.getQuantity() != null ? reqItem.getQuantity() : 1;
                BigDecimal price = reqItem.getUnitPrice() != null
                        ? reqItem.getUnitPrice()
                        : BigDecimal.ZERO;
				item = TicketItem.builder()
						.ticket(ticket)
						.description(reqItem.getDescription() != null
								? reqItem.getDescription() : "")
						.quantity(qty)
						.unitPrice(price)
						.originalPrice(reqItem.getOriginalPrice())
						.discountAmount(reqItem.getDiscountAmount())
						.subtotal(price.multiply(BigDecimal.valueOf(qty)))
						.build();
                ticket.getItems().add(item);
                newSubtotal = newSubtotal.add(item.getSubtotal());
            }

			ticket.setSubtotal(newSubtotal);
			BigDecimal discounts = ticket.getTotalDiscounts() != null
					? ticket.getTotalDiscounts()
					: BigDecimal.ZERO;
			ticket.setTotal(newSubtotal.subtract(discounts));
        }

        ticket = ticketRepository.save(ticket);
        log.info("Ticket {} actualizado — {} items", ticket.getId(), ticket.getItems().size());
        return toResponse(ticket);
    }

    @Transactional
    public void deleteTicket(Long id, User user) {
        Ticket ticket = ticketRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Ticket no encontrado"));
        deleteImageFile(ticket.getImagePath());
        ticketRepository.delete(ticket);
        log.info("Ticket {} eliminado por el usuario {}", id, user.getEmail());
    }

    private void enforceTicketLimit(User user) {
        List<Ticket> tickets = ticketRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
        while (tickets.size() >= maxTicketsPerUser) {
            Ticket oldest = tickets.remove(0);
            deleteImageFile(oldest.getImagePath());
            ticketRepository.delete(oldest);
            log.info("Eliminado ticket {} (mas antiguo) del usuario {} por limite de {} tickets",
                    oldest.getId(), user.getEmail(), maxTicketsPerUser);
        }
    }

    private void deleteImageFile(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return;
        for (String path : imagePath.split(",")) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty()) {
                try {
                    Files.deleteIfExists(Path.of(trimmed));
                } catch (IOException e) {
                    log.warn("No se pudo eliminar imagen {}: {}", trimmed, e.getMessage());
                }
            }
        }
    }

    private void validateImage(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("El archivo esta vacio");
        }

        String contentType = file.getContentType();
        if (contentType == null
                || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
            throw new IllegalArgumentException("Solo se aceptan imagenes (jpg, png, etc.) o PDF");
        }
    }

    private String saveImage(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(uploadDir);
            Files.createDirectories(uploadPath);

            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Error al guardar la imagen del ticket", e);
        }
    }

    private TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .storeName(ticket.getStoreName())
                .ticketId(ticket.getTicketId())
                .total(ticket.getTotal())
                .subtotal(ticket.getSubtotal())
                .totalDiscounts(ticket.getTotalDiscounts())
                .status(ticket.getStatus())
                .createdAt(ticket.getCreatedAt())
                .items(ticket.getItems().stream()
                        .map(this::toItemResponse)
                        .toList())
                .build();
    }

    private TicketItemResponse toItemResponse(TicketItem item) {
        return TicketItemResponse.builder()
                .id(item.getId())
                .description(item.getDescription())
                .rawDescription(item.getRawDescription())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .originalPrice(item.getOriginalPrice())
                .subtotal(item.getSubtotal())
                .barcode(item.getBarcode())
                .category(item.getCategory())
                .discountAmount(item.getDiscountAmount())
                .discountDescription(item.getDiscountDescription())
                .build();
    }
}
