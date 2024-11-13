package com.example.attendancetracking;

import java.util.List;

public class MainModel {
    private String name;
    private List<Float> embedding;

    // Constructor trống cho Firebase
    public MainModel() {
    }

    // Constructor đầy đủ
    public MainModel(String name, List<Float> embedding) {
        this.name = name;
        this.embedding = embedding;
    }

    // Getter và Setter cho name
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // Getter và Setter cho embedding
    public List<Float> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Float> embedding) {
        this.embedding = embedding;
    }
}
