package com.example.attendancetracking;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import com.firebase.ui.database.FirebaseRecyclerOptions;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.android.gms.location.*;
import com.google.android.gms.tasks.OnSuccessListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainActivity extends CameraActivity {

    RecyclerView recyclerView;
    MainAdapter mainAdapter;

    private static final String TAG = "MainActivity";
    private CameraBridgeViewBase mOpenCvCameraView;
    private static final int GALLERY_REQUEST_CODE = 1001;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 2001;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 2003;

    Button galleryBtn;
    Button showFacesBtn;
    ImageView imageView;

    private List<FaceData> faceDataList = new ArrayList<>();
    private DatabaseReference faceDataRef;

    // Location variables
    private FusedLocationProviderClient fusedLocationClient;
    private volatile Location currentLocation;
    private LocationCallback locationCallback;

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "Cannot connect to OpenCV Manager");
        } else {
            System.loadLibrary("attendancetracking");
        }
    }

    // Native methods
    public native void InitFaceDetector(String modelPath);
    public native int DetectFaces(long matAddrGray, long matAddrRgba, float[] largestFaceRect);
    public native void InitFaceRecognition(String modelPath);
    public native float[] ExtractFaceEmbedding(long matAddr);
    public native float CalculateSimilarity(float[] emb1, float[] emb2);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable Firebase persistence
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
            Log.d(TAG, "Firebase persistence enabled.");
        } catch (DatabaseException e) {
            Log.w(TAG, "Persistence already enabled.");
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mOpenCvCameraView = findViewById(R.id.opencv_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(cvCameraViewListener2);

        imageView = findViewById(R.id.imageView);

        galleryBtn = findViewById(R.id.galleryBtn);
        galleryBtn.setOnClickListener(view -> openGallery());

        showFacesBtn = findViewById(R.id.showFacesBtn);
        showFacesBtn.setOnClickListener(view -> displayRegisteredFaces());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    currentLocation = location;
                    Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());
                }
            }
        };

        requestPermissions();

        // Firebase Database reference
        faceDataRef = FirebaseDatabase.getInstance().getReference("faceDataList");
        Log.d(TAG, "Firebase Database reference initialized.");

        // Load face data from Firebase
        loadFaceDataList();

        recyclerView = findViewById(R.id.rv);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Use FirebaseRecyclerOptions
        FirebaseRecyclerOptions<MainModel> options = new FirebaseRecyclerOptions.Builder<MainModel>()
                .setQuery(faceDataRef, MainModel.class)
                .build();

        mainAdapter = new MainAdapter(options);
        recyclerView.setAdapter(mainAdapter);
    }

    private void requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
        } else {
            initFaceDetectionAndRecognition();
            mOpenCvCameraView.enableView();
            Log.d(TAG, "Camera permission already granted.");
        }

        // Request location permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
        } else {
            startLocationUpdates();
            Log.d(TAG, "Location permission already granted.");
        }
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        Log.d(TAG, "Requesting camera permission.");
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        Log.d(TAG, "Requesting location permission.");
    }

    private void initFaceDetectionAndRecognition() {
        // Face Detection
        try {
            InputStream inputStream = getAssets().open("face_detection_yunet_2023mar.onnx");
            FileUtil fileUtil = new FileUtil();
            java.io.File detectionModelFile = fileUtil.createTempFile(this, inputStream, "face_detection_yunet_2023mar.onnx");
            InitFaceDetector(detectionModelFile.getAbsolutePath());
            Log.d(TAG, "Face Detector initialized with model: " + detectionModelFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error initializing Face Detector: " + e.getMessage());
            e.printStackTrace();
        }

        // Face Recognition
        try {
            InputStream inputStream = getAssets().open("face_recognition_sface_2021dec.onnx");
            FileUtil fileUtil = new FileUtil();
            java.io.File recognitionModelFile = fileUtil.createTempFile(this, inputStream, "face_recognition_sface_2021dec.onnx");
            InitFaceRecognition(recognitionModelFile.getAbsolutePath());
            Log.d(TAG, "Face Recognition initialized with model: " + recognitionModelFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error initializing Face Recognition: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void openGallery() {
        // Use Storage Access Framework to pick an image without needing storage permissions
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, GALLERY_REQUEST_CODE);
        Log.d(TAG, "Opening gallery for image selection.");
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    private CameraBridgeViewBase.CvCameraViewListener2 cvCameraViewListener2 =
            new CameraBridgeViewBase.CvCameraViewListener2() {

                @Override
                public void onCameraViewStarted(int width, int height) {
                    Log.d(TAG, "Camera view started with width: " + width + " and height: " + height);
                }

                @Override
                public void onCameraViewStopped() {
                    Log.d(TAG, "Camera view stopped.");
                }

                @Override
                public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
                    long startTime = System.nanoTime();

                    Mat inputRgba = inputFrame.rgba();
                    Mat inputGray = inputFrame.gray();

                    float[] largestFaceRect = new float[4];

                    int numFaces = DetectFaces(inputGray.getNativeObjAddr(), inputRgba.getNativeObjAddr(), largestFaceRect);
                    Log.d(TAG, "Detected " + numFaces + " face(s).");

                    float[] cameraFrameEmbedding = ExtractFaceEmbedding(inputRgba.getNativeObjAddr());
                    if (cameraFrameEmbedding != null) {
                        Log.d(TAG, "Extracted face embedding from camera frame.");
                    } else {
                        Log.d(TAG, "No face embedding extracted from camera frame.");
                    }

                    if (cameraFrameEmbedding != null && !faceDataList.isEmpty()) {
                        String matchedName = null;
                        float highestSimilarity = 0.0f;

                        for (FaceData faceData : faceDataList) {
                            if (faceData.getEmbedding() == null) continue; // Kiểm tra embedding không null
                            float similarity = CalculateSimilarity(faceData.getEmbedding(), cameraFrameEmbedding);
                            if (similarity > 0.5f && similarity > highestSimilarity) {
                                highestSimilarity = similarity;
                                matchedName = faceData.getName();
                            }
                        }

                        float x = largestFaceRect[0];
                        float y = largestFaceRect[1];

                        if (matchedName != null) {
                            Imgproc.putText(inputRgba, matchedName,
                                    new Point(x, y - 10),
                                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(30, 30, 220), 2);
                            Log.d(TAG, "Matched user: " + matchedName + " with similarity: " + highestSimilarity);
                        } else {
                            Imgproc.putText(inputRgba, "Unknown",
                                    new Point(x, y - 10),
                                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 50, 50), 2);
                            Log.d(TAG, "No matching user found.");
                        }
                    }

                    long endTime = System.nanoTime();
                    long processingTimeMs = (endTime - startTime) / 1_000_000;

                    Imgproc.putText(inputRgba, "Processing Time: " + processingTimeMs + " ms",
                            new Point(10, 30),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 255, 255), 2);

                    if (currentLocation != null) {
                        String gpsText = String.format("Lat: %.5f, Lon: %.5f",
                                currentLocation.getLatitude(), currentLocation.getLongitude());

                        Imgproc.putText(inputRgba, gpsText,
                                new Point(10, 65),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(240, 240, 0), 2);
                    } else {
                        Imgproc.putText(inputRgba, "Location unavailable",
                                new Point(10, 65),
                                Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, new Scalar(255, 0, 0), 2);
                    }

                    Log.d(TAG, "Processing time per frame: " + processingTimeMs + " ms");

                    return inputRgba;
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();
        Log.d(TAG, "onResume: Camera view enabled.");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
        if (mainAdapter != null) {
            mainAdapter.startListening();
            Log.d(TAG, "onResume: MainAdapter started listening.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        Log.d(TAG, "onPause: Camera view disabled.");

        fusedLocationClient.removeLocationUpdates(locationCallback);
        Log.d(TAG, "onPause: Location updates removed.");
        if (mainAdapter != null) {
            mainAdapter.stopListening();
            Log.d(TAG, "onPause: MainAdapter stopped listening.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        Log.d(TAG, "onDestroy: Camera view disabled.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle image selection from gallery
        if (requestCode == GALLERY_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri imageUri = data.getData();
                if (imageUri != null) {
                    getContentResolver().takePersistableUriPermission(imageUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Log.d(TAG, "Image selected: " + imageUri.toString());

                    try {
                        Mat imgMat = uriToMat(imageUri);
                        float[] newEmbedding = ExtractFaceEmbedding(imgMat.getNativeObjAddr());

                        if (newEmbedding == null) {
                            Log.e(TAG, "No face detected in the selected image.");
                            Toast.makeText(this, "No face detected in the selected image.", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.d(TAG, "Face embedding extracted from gallery image.");

                            // Check for duplicates
                            String matchedName = null;
                            float highestSimilarity = 0.0f;
                            for (FaceData faceData : faceDataList) {
                                float similarity = CalculateSimilarity(faceData.getEmbedding(), newEmbedding);
                                if (similarity > 0.8f && similarity > highestSimilarity) {
                                    highestSimilarity = similarity;
                                    matchedName = faceData.getName();
                                }
                            }

                            if (matchedName != null) {
                                // Face already exists
                                handleDuplicateFace(matchedName, newEmbedding);
                            } else {
                                // No duplicate, proceed to add new face
                                promptForName(newEmbedding);
                            }
                        }

                    } catch (IOException e) {
                        Log.e(TAG, "Error processing selected image: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    Log.e(TAG, "Image URI is null");
                }
            } else {
                Log.e(TAG, "No image selected or action canceled");
            }
        }
    }

    private void handleDuplicateFace(String matchedName, float[] newEmbedding) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Already Exists");
        builder.setMessage("A face matching this one is already registered as \"" + matchedName + "\". What would you like to do?");

        builder.setPositiveButton("Update Entry", (dialog, which) -> {
            // Update the existing entry
            updateFaceData(matchedName, newEmbedding);
            Toast.makeText(this, "Face data updated.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Face data updated for user: " + matchedName);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            Toast.makeText(this, "Operation canceled.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Face data update canceled.");
        });

        builder.show();
    }

    private void updateFaceData(String name, float[] newEmbedding) {
        for (FaceData faceData : faceDataList) {
            if (faceData.getName().equals(name)) {
                faceData.setEmbedding(newEmbedding);
                saveFaceDataToFirebase(faceData);
                Log.d(TAG, "FaceData updated: " + name + " with new embedding.");
                break;
            }
        }
    }

    private void promptForName(float[] embedding) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Name");

        final EditText input = new EditText(this);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                // Check if name already exists
                boolean nameExists = false;
                for (FaceData faceData : faceDataList) {
                    if (faceData.getName().equalsIgnoreCase(name)) {
                        nameExists = true;
                        break;
                    }
                }

                if (nameExists) {
                    AlertDialog.Builder nameExistsDialog = new AlertDialog.Builder(this);
                    nameExistsDialog.setTitle("Name Already Exists");
                    nameExistsDialog.setMessage("An entry with this name already exists. Do you want to update it?");
                    nameExistsDialog.setPositiveButton("Update", (dialog1, which1) -> {
                        updateFaceData(name, embedding);
                        Toast.makeText(this, "Face data updated.", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Face data updated for user: " + name);
                    });
                    nameExistsDialog.setNegativeButton("Cancel", (dialog1, which1) -> dialog1.cancel());
                    nameExistsDialog.show();
                } else {
                    // Save new face data
                    String userId = UUID.randomUUID().toString();
                    FaceData faceData = new FaceData(userId, name, embedding);

                    saveFaceDataToFirebase(faceData);
                    Toast.makeText(this, "Face data saved.", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "FaceData saved: " + name + " with userId: " + userId);
                }
            } else {
                Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "User attempted to save face data without entering a name.");
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private Mat uriToMat(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Unable to open input stream from URI");
        }
        Bitmap bitmap = BitmapFactory.decodeStream(in);
        in.close();
        if (bitmap == null)  {
            throw new IOException("Unable to decode bitmap from URI");
        }
        Mat mat = new Mat();
        Bitmap bmp32 = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Utils.bitmapToMat(bmp32, mat);
        return mat;
    }

    private void saveFaceDataToFirebase(FaceData faceData) {
        // Convert embedding array to a List<Float>
        List<Float> embeddingList = new ArrayList<>();
        for (float val : faceData.getEmbedding()) {
            embeddingList.add(val);
        }

        // Create a MainModel object
        MainModel mainModel = new MainModel(faceData.getName(), embeddingList);

        // Save to Firebase with unique ID
        faceDataRef.child(faceData.getUserId()).setValue(mainModel)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Face data saved for user: " + faceData.getName() + " with userId: " + faceData.getUserId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save face data for user: " + faceData.getName() + " Error: " + e.getMessage());
                });
    }

    private void loadFaceDataList() {
        faceDataRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                try {
                    MainModel mainModel = snapshot.getValue(MainModel.class);
                    String userId = snapshot.getKey();
                    if (mainModel != null && mainModel.getEmbedding() != null && mainModel.getEmbedding() instanceof List && userId != null) {
                        List<Float> embeddingList = mainModel.getEmbedding();
                        float[] embeddingArray = new float[embeddingList.size()];
                        for (int i = 0; i < embeddingList.size(); i++) {
                            embeddingArray[i] = embeddingList.get(i);
                        }
                        faceDataList.add(new FaceData(userId, mainModel.getName(), embeddingArray));
                        Log.d(TAG, "Loaded FaceData: " + mainModel.getName() + " with userId: " + userId);
                    } else {
                        Log.e(TAG, "Invalid data in Firebase. userId: " + snapshot.getKey());
                    }
                } catch (DatabaseException e) {
                    Log.e(TAG, "DatabaseException: " + e.getMessage());
                }
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                try {
                    MainModel mainModel = snapshot.getValue(MainModel.class);
                    String userId = snapshot.getKey();
                    if (mainModel != null && mainModel.getEmbedding() != null && mainModel.getEmbedding() instanceof List && userId != null) {
                        List<Float> embeddingList = mainModel.getEmbedding();
                        float[] embeddingArray = new float[embeddingList.size()];
                        for (int i = 0; i < embeddingList.size(); i++) {
                            embeddingArray[i] = embeddingList.get(i);
                        }
                        // Update data in faceDataList
                        for (int i = 0; i < faceDataList.size(); i++) {
                            if (faceDataList.get(i).getUserId().equals(userId)) {
                                faceDataList.set(i, new FaceData(userId, mainModel.getName(), embeddingArray));
                                Log.d(TAG, "Updated FaceData: " + mainModel.getName() + " with userId: " + userId);
                                break;
                            }
                        }
                    } else {
                        Log.e(TAG, "Invalid data in Firebase onChildChanged. userId: " + snapshot.getKey());
                    }
                } catch (DatabaseException e) {
                    Log.e(TAG, "DatabaseException onChildChanged: " + e.getMessage());
                }
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                String userId = snapshot.getKey();
                if (userId != null) {
                    faceDataList.removeIf(faceData -> faceData.getUserId().equals(userId));
                    Log.d(TAG, "Removed FaceData with userId: " + userId);
                } else {
                    Log.e(TAG, "Snapshot key (userId) is null in onChildRemoved.");
                }
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                // Not needed in this case
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to load face data from Firebase.", error.toException());
            }
        });
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
        } else {
            LocationRequest locationRequest = LocationRequest.create();
            locationRequest.setInterval(10000); // 10 seconds
            locationRequest.setFastestInterval(1000); // 1 second
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            fusedLocationClient.requestLocationUpdates(locationRequest,
                    locationCallback,
                    Looper.getMainLooper());
            Log.d(TAG, "Started location updates.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initFaceDetectionAndRecognition();
                mOpenCvCameraView.enableView();
                Log.d(TAG, "Camera permission granted.");
            } else {
                Toast.makeText(this, "Camera permission is required for face detection.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Camera permission denied.");
                finish();
            }
        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
                Log.d(TAG, "Location permission granted.");
            } else {
                Toast.makeText(this, "Location permission is required to display GPS coordinates.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Location permission denied.");
            }
        }
    }

    private void displayRegisteredFaces() {
        if (faceDataList.isEmpty()) {
            Toast.makeText(this, "No faces registered.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "No faces registered to display.");
            return;
        }

        // Use a ListView to display the faces
        ListView listView = new ListView(this);

        // Prepare data for the adapter
        List<String> names = new ArrayList<>();
        for (FaceData faceData : faceDataList) {
            names.add(faceData.getName() + " (ID: " + faceData.getUserId() + ")");
        }

        // Create an ArrayAdapter
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);

        // Set item click listener to show embeddings and delete option
        listView.setOnItemClickListener((parent, view, position, id) -> {
            FaceData selectedFace = faceDataList.get(position);
            Log.d(TAG, "Selected FaceData: " + selectedFace.getName() + " with userId: " + selectedFace.getUserId());

            // Show embedding details
            AlertDialog.Builder embeddingDialog = new AlertDialog.Builder(this);
            embeddingDialog.setTitle("Embedding for " + selectedFace.getName());

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < selectedFace.getEmbedding().length; i++) {
                sb.append(String.format("%.4f", selectedFace.getEmbedding()[i]));
                if (i < selectedFace.getEmbedding().length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");

            String embeddingString = sb.toString();

            embeddingDialog.setMessage(embeddingString);

            // Add buttons
            embeddingDialog.setNeutralButton("Copy Embedding", (dialogInterface, i) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("", embeddingString);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Embedding copied to clipboard.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Embedding copied to clipboard for user: " + selectedFace.getName());
            });

            embeddingDialog.setPositiveButton("Delete User", (dialogInterface, i) -> {
                // Confirm deletion
                AlertDialog.Builder confirmDialog = new AlertDialog.Builder(this);
                confirmDialog.setTitle("Delete User");
                confirmDialog.setMessage("Are you sure you want to delete \"" + selectedFace.getName() + "\"?");
                confirmDialog.setPositiveButton("Yes", (dialog, which) -> {
                    deleteUser(selectedFace);
                });
                confirmDialog.setNegativeButton("No", (dialog, which) -> dialog.cancel());
                confirmDialog.show();
            });

            embeddingDialog.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());

            embeddingDialog.show();
        });

        // Display the ListView in a dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Registered Faces");
        builder.setView(listView);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
        Log.d(TAG, "Displayed registered faces.");
    }

    private void deleteUser(FaceData faceData) {
        String userId = faceData.getUserId();
        if (userId != null && !userId.isEmpty()) {
            Log.d(TAG, "Attempting to delete user: " + faceData.getName() + " with userId: " + userId);
            faceDataRef.child(userId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "User \"" + faceData.getName() + "\" deleted.", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Successfully deleted user: " + faceData.getName() + " with userId: " + userId);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Failed to delete user: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Failed to delete user: " + faceData.getName() + " with userId: " + userId, e);
                    });
        } else {
            Toast.makeText(this, "Invalid user ID. Cannot delete user.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Cannot delete user. userId is null or empty for user: " + faceData.getName());
        }
    }
}
