package com.skillbridge.bulkupload.service;

import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.bean.HeaderColumnNameMappingStrategy;
import com.skillbridge.bulkupload.dto.StudentUploadDTO;
import com.skillbridge.bulkupload.dto.TrainerUploadDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@Slf4j
public class CsvParserService {

    public List<StudentUploadDTO> parseStudentCsv(MultipartFile file) {
        return parseCsv(file, StudentUploadDTO.class);
    }

    public List<TrainerUploadDTO> parseTrainerCsv(MultipartFile file) {
        return parseCsv(file, TrainerUploadDTO.class);
    }

    private <T> List<T> parseCsv(MultipartFile file, Class<T> clazz) {
        try (Reader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            // First, read and log the header line
            BufferedReader headerReader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String headerLine = headerReader.readLine();
            log.info("CSV Headers found: {}", headerLine);
            log.info("Parsing CSV for class: {}", clazz.getSimpleName());
            headerReader.close();

            // Now parse with a fresh reader
            Reader actualReader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));

            HeaderColumnNameMappingStrategy<T> strategy = new HeaderColumnNameMappingStrategy<>();
            strategy.setType(clazz);

            CsvToBean<T> csvToBean = new CsvToBeanBuilder<T>(reader)
                    .withMappingStrategy(strategy)
                    .withIgnoreLeadingWhiteSpace(true)
                    .withType(clazz)
                    .withThrowExceptions(true)
                    .build();

            List<T> results = csvToBean.parse();
            log.info("Successfully parsed {} rows from CSV", results.size());
            return results;
        } catch (IOException e) {
            log.error("Error parsing CSV file", e);
            throw new RuntimeException("Failed to parse CSV file: " + e.getMessage());
        } catch (Exception e) {
            log.error(
                    "Error processing CSV data. Expected headers for {}: Full Name, Email, Roll Number, Degree, Branch, Year",
                    clazz.getSimpleName(), e);
            throw new RuntimeException("Error processing CSV data: " + e.getMessage() +
                    ". Please ensure CSV has correct headers: Full Name, Email, Roll Number, Degree, Branch, Year");
        }
    }

    public void validateCsvFormat(MultipartFile file, String entityType) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (!isCsvFile(file)) {
            throw new IllegalArgumentException("File must be a CSV");
        }

        // Additional validation can be added here (headers check, etc.)
    }

    private boolean isCsvFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && (contentType.equals("text/csv") || contentType.equals("application/vnd.ms-excel"))) {
            return true;
        }
        // Fallback check extension
        String fileName = file.getOriginalFilename();
        return fileName != null && fileName.toLowerCase().endsWith(".csv");
    }

    public byte[] generateStudentTemplate() {
        String header = "Full Name,Email,Roll Number,Degree,Branch,Year\n";
        String example = "John Doe,john.doe@college.edu,CS2024001,B.Tech,Computer Science,3\n";
        return (header + example).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] generateTrainerTemplate() {
        String header = "Full Name,Email,Department,Specialization\n";
        String example = "Dr. Robert Smith,robert.smith@college.edu,Computer Science,Machine Learning\n";
        return (header + example).getBytes(StandardCharsets.UTF_8);
    }
}
