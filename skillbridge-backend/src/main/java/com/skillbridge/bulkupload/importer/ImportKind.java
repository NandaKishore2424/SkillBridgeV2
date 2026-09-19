package com.skillbridge.bulkupload.importer;

import java.util.ArrayList;
import java.util.List;

/** What a CSV file creates, and the columns it must and may have. */
public enum ImportKind {

    STUDENT(List.of("Full Name", "Email", "Roll Number"), List.of("Degree", "Branch", "Year"),
            "Asha Verma,asha.verma@college.edu,CS2024001,B.Tech,Computer Science,3"),
    TRAINER(List.of("Full Name", "Email"), List.of("Department", "Specialization"),
            "Dr. Robert Smith,robert.smith@college.edu,Computer Science,Machine Learning");

    private final List<String> required;
    private final List<String> optional;
    private final String exampleRow;

    ImportKind(List<String> required, List<String> optional, String exampleRow) {
        this.required = required;
        this.optional = optional;
        this.exampleRow = exampleRow;
    }

    /** A downloadable starting point: the header and one example row. */
    public String template() {
        return String.join(",", columns()) + "\n" + exampleRow + "\n";
    }

    public List<String> required() {
        return required;
    }

    /** Required then optional, in template order. */
    public List<String> columns() {
        List<String> all = new ArrayList<>(required);
        all.addAll(optional);
        return all;
    }
}
