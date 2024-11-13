package com.example.attendancetracking;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FileUtil {
    public File createTempFile(Context context, InputStream inputStream, String filename) throws IOException {
        File tempFile = new File(context.getCacheDir(), filename);
        if (tempFile.exists()) {
            return tempFile;
        }

        FileOutputStream outputStream = new FileOutputStream(tempFile);
        byte[] buffer = new byte[2048];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }

        inputStream.close();
        outputStream.close();

        return tempFile;
    }
}
