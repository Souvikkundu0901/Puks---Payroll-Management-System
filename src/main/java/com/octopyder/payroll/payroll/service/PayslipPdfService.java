package com.octopyder.payroll.payroll.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.octopyder.payroll.payroll.PayrollLine;
import com.octopyder.payroll.payroll.PayrollLineKind;
import com.octopyder.payroll.payroll.PayrollRecord;
import com.octopyder.payroll.people.EmployeeProfile;
import com.octopyder.payroll.people.User;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PayslipPdfService {

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.DARK_GRAY);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
    private static final Font BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
    private static final Font NORMAL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);

    public byte[] generatePayslipPdf(
        PayrollRecord record,
        User user,
        EmployeeProfile profile,
        String departmentName,
        String maskedAccount,
        String bankName,
        String ifsc,
        List<PayrollLine> lines,
        String companyName
    ) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            // 1. Header
            Paragraph compPara = new Paragraph(companyName != null ? companyName : "Puk's", TITLE_FONT);
            compPara.setAlignment(Element.ALIGN_CENTER);
            document.add(compPara);

            String monthStr = record.getMonth().format(DateTimeFormatter.ofPattern("MMMM yyyy"));
            Paragraph titlePara = new Paragraph("PAYSLIP FOR " + monthStr.toUpperCase(), SUBTITLE_FONT);
            titlePara.setAlignment(Element.ALIGN_CENTER);
            titlePara.setSpacingAfter(15f);
            document.add(titlePara);

            // 2. Employee & Bank Info Table
            PdfPTable infoTable = new PdfPTable(4);
            infoTable.setWidthPercentage(100);
            infoTable.setSpacingAfter(15f);

            addInfoCell(infoTable, "Employee Code:", true);
            addInfoCell(infoTable, user.getCode(), false);
            addInfoCell(infoTable, "Employee Name:", true);
            addInfoCell(infoTable, (profile != null && profile.getFirstName() != null) ? profile.getFirstName() + " " + (profile.getLastName() != null ? profile.getLastName() : "") : user.getEmail(), false);

            addInfoCell(infoTable, "Department:", true);
            addInfoCell(infoTable, departmentName != null ? departmentName : "General", false);
            addInfoCell(infoTable, "Designation:", true);
            addInfoCell(infoTable, (profile != null && profile.getDesignation() != null) ? profile.getDesignation() : "Employee", false);

            addInfoCell(infoTable, "Working Days:", true);
            addInfoCell(infoTable, String.valueOf(record.getWorkingDays()), false);
            addInfoCell(infoTable, "Present Days:", true);
            addInfoCell(infoTable, record.getPresentDays().toPlainString(), false);

            addInfoCell(infoTable, "Bank Name:", true);
            addInfoCell(infoTable, bankName != null ? bankName : "N/A", false);
            addInfoCell(infoTable, "Bank Account:", true);
            addInfoCell(infoTable, maskedAccount != null ? maskedAccount : "XXXX-XXXX-XXXX", false);

            addInfoCell(infoTable, "IFSC Code:", true);
            addInfoCell(infoTable, ifsc != null ? ifsc : "N/A", false);
            addInfoCell(infoTable, "Status:", true);
            addInfoCell(infoTable, record.getStatus().name(), false);

            document.add(infoTable);

            // 3. Earnings & Deductions Table
            PdfPTable linesTable = new PdfPTable(4);
            linesTable.setWidthPercentage(100);
            linesTable.setWidths(new float[]{40f, 15f, 30f, 15f});
            linesTable.setSpacingAfter(15f);

            // Header row
            addHeaderCell(linesTable, "Earnings");
            addHeaderCell(linesTable, "Amount (INR)");
            addHeaderCell(linesTable, "Deductions");
            addHeaderCell(linesTable, "Amount (INR)");

            List<PayrollLine> earnings = lines.stream().filter(l -> l.getKind() == PayrollLineKind.EARNING).toList();
            List<PayrollLine> deductions = lines.stream().filter(l -> l.getKind() == PayrollLineKind.DEDUCTION || l.getKind() == PayrollLineKind.RETENTION).toList();

            int maxRows = Math.max(earnings.size(), deductions.size());
            BigDecimal totalEarnings = BigDecimal.ZERO;
            BigDecimal totalDeductions = BigDecimal.ZERO;

            for (int i = 0; i < maxRows; i++) {
                if (i < earnings.size()) {
                    PayrollLine e = earnings.get(i);
                    totalEarnings = totalEarnings.add(e.getAmount());
                    addCell(linesTable, e.getLabel(), Element.ALIGN_LEFT);
                    addCell(linesTable, e.getAmount().toPlainString(), Element.ALIGN_RIGHT);
                } else {
                    addCell(linesTable, "", Element.ALIGN_LEFT);
                    addCell(linesTable, "", Element.ALIGN_RIGHT);
                }

                if (i < deductions.size()) {
                    PayrollLine d = deductions.get(i);
                    totalDeductions = totalDeductions.add(d.getAmount());
                    addCell(linesTable, d.getLabel(), Element.ALIGN_LEFT);
                    addCell(linesTable, d.getAmount().toPlainString(), Element.ALIGN_RIGHT);
                } else {
                    addCell(linesTable, "", Element.ALIGN_LEFT);
                    addCell(linesTable, "", Element.ALIGN_RIGHT);
                }
            }

            // Total row
            addTotalCell(linesTable, "Total Earnings", Element.ALIGN_LEFT);
            addTotalCell(linesTable, "INR " + totalEarnings.toPlainString(), Element.ALIGN_RIGHT);
            addTotalCell(linesTable, "Total Deductions", Element.ALIGN_LEFT);
            addTotalCell(linesTable, "INR " + totalDeductions.toPlainString(), Element.ALIGN_RIGHT);

            document.add(linesTable);

            // 4. Net Summary Box
            PdfPTable netTable = new PdfPTable(2);
            netTable.setWidthPercentage(100);
            netTable.setWidths(new float[]{70f, 30f});
            netTable.setSpacingAfter(20f);

            PdfPCell wordsCell = new PdfPCell(new Phrase("Net Payable in Words: " + convertToWords(record.getNetPayable()), BOLD_FONT));
            wordsCell.setBackgroundColor(new Color(245, 245, 245));
            wordsCell.setPadding(8f);
            netTable.addCell(wordsCell);

            PdfPCell netCell = new PdfPCell(new Phrase("NET PAYABLE: INR " + record.getNetPayable().toPlainString(), TITLE_FONT));
            netCell.setBackgroundColor(new Color(230, 245, 230));
            netCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            netCell.setPadding(8f);
            netTable.addCell(netCell);

            document.add(netTable);

            // 5. Payment details (if paid)
            if (record.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
                Paragraph payPara = new Paragraph(
                    "Payment Details: Paid INR " + record.getPaidAmount().toPlainString() +
                    (record.getPaymentDate() != null ? " on " + record.getPaymentDate() : "") +
                    (record.getPaymentRef() != null ? " (Ref: " + record.getPaymentRef() + ")" : "") +
                    (record.getPaymentMethod() != null ? " via " + record.getPaymentMethod() : ""),
                    BOLD_FONT
                );
                payPara.setSpacingAfter(15f);
                document.add(payPara);
            }

            // 6. Footer
            Paragraph footer = new Paragraph("This is a system-generated document and requires no physical signature.", FOOTER_FONT);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate payslip PDF", e);
        }
    }

    private void addInfoCell(PdfPTable table, String text, boolean isBold) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", isBold ? BOLD_FONT : NORMAL_FONT));
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(3f);
        table.addCell(cell);
    }

    private void addHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, SECTION_FONT));
        cell.setBackgroundColor(new Color(235, 235, 235));
        cell.setPadding(5f);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, NORMAL_FONT));
        cell.setHorizontalAlignment(align);
        cell.setPadding(4f);
        table.addCell(cell);
    }

    private void addTotalCell(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BOLD_FONT));
        cell.setBackgroundColor(new Color(245, 245, 245));
        cell.setHorizontalAlignment(align);
        cell.setPadding(5f);
        table.addCell(cell);
    }

    private String convertToWords(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return "Zero Rupees Only";
        }
        long rupees = amount.longValue();
        int paise = amount.remainder(BigDecimal.ONE).multiply(new BigDecimal("100")).intValue();
        return rupees + " Rupees" + (paise > 0 ? " and " + paise + " Paise" : "") + " Only";
    }
}
