package com.octopyder.payroll.people;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;

@Service
public class IdCardPdfService {

    private static final Font COMPANY_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, new Color(220, 53, 69)); // Primary red
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.DARK_GRAY);
    private static final Font NAME_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.BLACK);
    private static final Font DESIGNATION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(108, 117, 125));
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.DARK_GRAY);
    private static final Font VALUE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);

    public byte[] generateIdCardPdf(
        User user,
        EmployeeProfile profile,
        String departmentName,
        String companyName
    ) {
        // Standard ID card size: 242 x 380 points (portrait badge)
        Rectangle pageSize = new Rectangle(280, 420);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(pageSize, 15, 15, 15, 15);
            PdfWriter.getInstance(document, out);
            document.open();

            // Outer Card Table
            PdfPTable card = new PdfPTable(1);
            card.setWidthPercentage(100);

            PdfPCell mainCell = new PdfPCell();
            mainCell.setBorderColor(new Color(220, 53, 69));
            mainCell.setBorderWidth(2);
            mainCell.setPadding(12);

            // 1. Company Name & Subtitle
            Paragraph compName = new Paragraph(companyName != null && !companyName.isBlank() ? companyName : "Puk's", COMPANY_FONT);
            compName.setAlignment(Element.ALIGN_CENTER);
            mainCell.addElement(compName);

            Paragraph badgeTitle = new Paragraph("EMPLOYEE IDENTITY CARD", SUBTITLE_FONT);
            badgeTitle.setAlignment(Element.ALIGN_CENTER);
            badgeTitle.setSpacingAfter(10f);
            mainCell.addElement(badgeTitle);

            // 2. Employee Name & Designation
            String fullName = profile != null && profile.getFirstName() != null
                ? profile.getFirstName() + " " + (profile.getLastName() != null ? profile.getLastName() : "")
                : user.getCode();

            Paragraph namePara = new Paragraph(fullName, NAME_FONT);
            namePara.setAlignment(Element.ALIGN_CENTER);
            mainCell.addElement(namePara);

            String desig = profile != null && profile.getDesignation() != null ? profile.getDesignation() : "Employee";
            Paragraph desigPara = new Paragraph(desig, DESIGNATION_FONT);
            desigPara.setAlignment(Element.ALIGN_CENTER);
            desigPara.setSpacingAfter(12f);
            mainCell.addElement(desigPara);

            // 3. Information Grid
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{38f, 62f});

            addInfoRow(infoTable, "Employee ID:", user.getCode());
            addInfoRow(infoTable, "Department:", departmentName != null ? departmentName : "General");
            addInfoRow(infoTable, "Email:", user.getEmail());
            addInfoRow(infoTable, "Phone:", profile != null && profile.getPhone() != null ? profile.getPhone() : "-");
            addInfoRow(infoTable, "Blood Group:", profile != null && profile.getBloodGroup() != null ? profile.getBloodGroup() : "-");
            addInfoRow(infoTable, "Emergency:", profile != null && profile.getEmergencyContact() != null ? profile.getEmergencyContact() : "-");
            if (profile != null && profile.getJoiningDate() != null) {
                addInfoRow(infoTable, "Joined:", profile.getJoiningDate().toString());
            }

            mainCell.addElement(infoTable);

            // 4. Footer Note
            Paragraph footer = new Paragraph("\nAuthorized Official Identity Card\nPuk's Payroll Management System", FOOTER_FONT);
            footer.setAlignment(Element.ALIGN_CENTER);
            mainCell.addElement(footer);

            card.addCell(mainCell);
            document.add(card);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ID card PDF: " + e.getMessage(), e);
        }
    }

    private void addInfoRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPaddingBottom(4f);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, VALUE_FONT));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPaddingBottom(4f);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }
}
