package sda.pl.zdjavapol96.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sda.pl.zdjavapol96.dto.DocumentElementDto;
import sda.pl.zdjavapol96.model.*;
import sda.pl.zdjavapol96.exception.DocumentAlreadyAccepted;
import sda.pl.zdjavapol96.exception.NotEnoughProductOnStock;
import sda.pl.zdjavapol96.exception.ProductIsNotSalable;
import sda.pl.zdjavapol96.repository.DocumentElementRepository;
import sda.pl.zdjavapol96.repository.DocumentRepository;
import sda.pl.zdjavapol96.repository.ProductPriceRepository;
import sda.pl.zdjavapol96.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JpaDocumentElementService implements DocumentElementService {

    private final DocumentRepository documentRepository;
    private final DocumentElementRepository documentElementRepository;
    private final ProductRepository productRepository;
    private final ProductPriceRepository productPriceRepository;

    @Override
    @Transactional
    public DocumentElement add(DocumentElementDto newDocumentElement) {
        if (isAccepted(newDocumentElement)) {
            throw new DocumentAlreadyAccepted("Dokument już zaakceptowany", newDocumentElement.getDocumentId());
        } else {
            List<ProductPrice> pricesByProductId = productPriceRepository.findProductPricesByProductId(newDocumentElement.getProductId());
            pricesByProductId.sort((p1, p2) -> takeNewestPrice(newDocumentElement, p1, p2));
            Optional<ProductPrice> first = pricesByProductId.stream().findFirst();
            Product product1 = productRepository.getById(newDocumentElement.getProductId());
            if (isNotSalable(newDocumentElement, product1)) {
                throw new ProductIsNotSalable("Produkt nie jest na sprzedaż", newDocumentElement.getProductId());
            } else {
                DocumentElement documentElement = createDocumentElement(newDocumentElement, first);
                DocumentElement save = documentElementRepository.save(documentElement);
                Product product = productRepository.getById(newDocumentElement.getProductId());
                if (documentElement.getDocument().getDocumentType() == DocumentType.GOODS_RECEIVED_NOTE) {
                    setQuantity(newDocumentElement, product);
                }
                if (documentElement.getDocument().getDocumentType() == DocumentType.STOCK_ISSUE_CONFIRMATION) {
                    stockIssue(newDocumentElement, product);
                }
                Document document = documentRepository.getById(newDocumentElement.getDocumentId());
                BigDecimal totalNet = BigDecimal.ZERO;
                BigDecimal totalGross = BigDecimal.ZERO;
                for (DocumentElement element : document.getDocumentElements()) {
                    if (isSalesDocuments(documentElement)) {
                        for (int i = 0; i < element.getQuantity().intValue(); i++) {
                            totalNet = totalNet.add(element.getProductPrice().getSellingPrice());
                            totalGross = getSellingTotalGross(totalGross, element);
                        }
                    } else {
                        for (int i = 0; i < element.getQuantity().intValue(); i++) {
                            totalNet = totalNet.add(element.getProductPrice().getPurchasePrice());
                            totalGross = getPurchaseTotalGross(totalGross, element);
                        }
                    }
                }
                document.setTotalGros(totalGross);
                document.setTotalNet(totalNet);
                documentRepository.save(document);
                return save;
            }
        }
    }

    private boolean isAccepted(DocumentElementDto newDocumentElement) {
        return Boolean.TRUE.equals(documentRepository.getById(newDocumentElement.getDocumentId()).getAccepted());
    }

    private boolean isNotSalable(DocumentElementDto newDocumentElement, Product product1) {
        return Boolean.FALSE.equals(product1.getIsSaleable()) &&
                documentRepository.getById(newDocumentElement.getDocumentId()).getDocumentType() == DocumentType.SALES_INVOICE;
    }

    private boolean isSalesDocuments(DocumentElement documentElement) {
        return documentElement.getDocument().getDocumentType() == DocumentType.SALES_INVOICE
                || documentElement.getDocument().getDocumentType() == DocumentType.STOCK_ISSUE_CONFIRMATION;
    }

    private BigDecimal getPurchaseTotalGross(BigDecimal totalGross, DocumentElement element) {
        return totalGross.add(element.getProductPrice().getPurchasePrice()
                .multiply(element.getProduct().getVat())
                .divide(BigDecimal.valueOf(100))
                .add(element.getProductPrice().getPurchasePrice()));
    }

    private BigDecimal getSellingTotalGross(BigDecimal totalGross, DocumentElement element) {
        return totalGross.add(element.getProductPrice().getSellingPrice()
                .multiply(element.getProduct().getVat())
                .divide(BigDecimal.valueOf(100))
                .add(element.getProductPrice().getSellingPrice()));
    }

    private int takeNewestPrice(DocumentElementDto newDocumentElement, ProductPrice p1, ProductPrice p2) {
        if (p1.getIntroductionDate().isBefore(p2.getIntroductionDate()) &&
                p1.getIntroductionDate().isBefore(documentRepository.getById(newDocumentElement.getDocumentId()).getIssueDate()))
            return 1;
        else return -1;
    }

    private void setQuantity(DocumentElementDto newDocumentElement, Product product) {
        BigDecimal quantity = product.getQuantity();
        BigDecimal result = quantity.add(newDocumentElement.getQuantity());
        product.setQuantity(result);
        productRepository.save(product);
    }

    private void stockIssue(DocumentElementDto newDocumentElement, Product product) {
        if (newDocumentElement.getQuantity().compareTo(product.getQuantity()) > 0) {
            throw new NotEnoughProductOnStock("Zbyt mała ilość produktu w magazynie : ", product.getQuantity());
        } else {
            BigDecimal quantity = product.getQuantity();
            BigDecimal result = quantity.subtract(newDocumentElement.getQuantity());
            product.setQuantity(result);
            productRepository.save(product);
        }
    }

    private DocumentElement createDocumentElement(DocumentElementDto newDocumentElement, Optional<ProductPrice> first) {
        return DocumentElement.builder()
                .document(documentRepository.getById(newDocumentElement.getDocumentId()))
                .product(productRepository.getById(newDocumentElement.getProductId()))
                .quantity(newDocumentElement.getQuantity())
                .productPrice(first.orElseThrow())
                .build();
    }

    @Override
    public List<DocumentElement> findAll() {
        return documentElementRepository.findAll();
    }

    @Override
    public Optional<DocumentElement> findById(long id) {
        return documentElementRepository.findById(id);
    }

    @Override
    public void deleteById(long id) {
        documentElementRepository.deleteById(id);
    }
}