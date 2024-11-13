package com.example.attendancetracking;

public class FaceData {
    private String userId; // Thêm userId
    private String name;
    private float[] embedding;

    // Constructor trống cho Firebase
    public FaceData() {
    }

    // Constructor đầy đủ
    public FaceData(String userId, String name, float[] embedding) {
        this.userId = userId;
        this.name = name;
        this.embedding = embedding;
    }

    // Getter và Setter cho userId
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    // Getter và Setter cho name
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // Getter và Setter cho embedding
    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
