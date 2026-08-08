package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.request.UpdateTicketRequest;
import ar.edu.ofertAR.dto.response.TicketItemResponse;
import ar.edu.ofertAR.dto.response.TicketResponse;
import ar.edu.ofertAR.model.Ticket;
import ar.edu.ofertAR.model.TicketItem;
import ar.edu.ofertAR.model.TicketStatus;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketProcessingService ticketProcessingService;
    private final ExecutorService ticketProcessingExecutor;

    @Value("${ticket.upload-dir:uploads/tickets}")
    private String uploadDir;

    // Was a hardcoded constant (3) â€” raised and made configurable so "productos
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
        // thread, so the bytes have to be pulled before handing the work off.
        List<TicketProcessingService.PagePayload> pages = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                pages.add(new TicketProcessingService.PagePayload(file.getBytes(), file.getContentType()));
            } catch (IOException e) {
                log.error("Error al leer archivo del ticket {}: {}", ticket.getId(), e.getMessage());
                ticket.setStatus(TicketStatus.FAILED);
                ticketRepository.save(ticket);
                return toResponse(ticket);
            }
        }

        // Hand the OCR to a background worker and answer right away. The user
        // can keep using the app, and because the work lives on the server it
        // finishes even if they lose connectivity or close the app.
        //
        // Queued only once this transaction has committed. The worker looks
        // the ticket up by id from its own thread, so while the INSERT is
        // still uncommitted that lookup finds nothing and the worker discards
        // the job — leaving the ticket stuck in PENDING with nothing to
        // retry it. Submitting from afterCommit() is what makes the row
        // visible before anyone goes looking for it.
        final Long ticketId = ticket.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ticketProcessingExecutor.submit(() -> {
                    try {
                        ticketProcessingService.process(ticketId, pages);
                    } catch (Exception e) {
                        log.error("Procesamiento en segundo plano fallo para el ticket {}: {}",
                                ticketId, e.getMessage(), e);
                    }
                });
            }
        });

        log.info("Ticket {} encolado para procesamiento ({} paginas)", ticketId, pages.size());
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

        // Corrections are only meaningful on a finished ticket, and only on
        // the first pass: once confirmed, the figures feed the savings history
        // and recurring-product stats, so they stop being editable.
        if (ticket.getStatus() != TicketStatus.PROCESSED) {
            throw new IllegalArgumentException("El ticket todavía se está procesando");
        }
        if (ticket.isReviewed()) {
            throw new IllegalArgumentException("Este ticket ya fue confirmado y no puede modificarse");
        }

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

        // Confirming is what closes the editing window; from here the ticket
        // is read-only.
        ticket.setReviewed(true);

        ticket = ticketRepository.save(ticket);
        log.info("Ticket {} actualizado y confirmado — {} items", ticket.getId(), ticket.getItems().size());
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
                .reviewed(ticket.isReviewed())
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
