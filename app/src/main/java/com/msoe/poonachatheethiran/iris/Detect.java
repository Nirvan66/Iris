package com.msoe.poonachatheethiran.iris;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
/**
 * Created by poonachatheethiran on 5/14/2018.
 * This class is used for setting up video stream and
 * handling detection of objects in video stream.
 */

public class Detect extends AppCompatActivity {
    private static final String TAG = "Detect";
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
    private TextureView textureView;
    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean mFlashSupported;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private Handler detectionHandler;
    private HandlerThread detectioThread;

    private ImageView imageView;
    private Object lock = new Object();
    private boolean runDetector = false;
    private boolean runDisplay = false;
    private Classifier imageDetector;
    private List<Classifier.Recognition> detection_results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detect);
        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        imageView=(ImageView)findViewById(R.id.imageViewResult);
        Button b= (Button) findViewById(R.id.btnClassify);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Detect.this, Classify.class);
                startActivity(intent);
            }
        });
        try {
            imageDetector = ImageDetector.create(
                    Detect.this.getAssets(),
                    "mobilenet_ssd.tflite",
                    "coco_labels_list.txt",
                    300);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize image detector");
        }
    }
    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        detectioThread= new HandlerThread("Detection Background");
        detectioThread.start();
        detectionHandler= new Handler(detectioThread.getLooper());
        synchronized (lock) {
            runDetector = true;
            runDisplay=true;
        }
        mBackgroundHandler.post(frameStream);
        detectionHandler.post(periodicDetect);
    }
    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        detectioThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;

            detectioThread.join();
            detectioThread=null;
            detectionHandler=null;
            synchronized (lock) {
                runDetector = false;
                runDisplay=false;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    /**
     * Takes displays video stream.
     */
    private Runnable frameStream =
            new Runnable() {
                @Override
                public void run() {
                        if (runDisplay) {
                            displayFrame();
                        }
                    mBackgroundHandler.post(frameStream);
                }
            };

    /**
     * Takes frames and runs inference them periodically.
     */
    private Runnable periodicDetect =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        if (runDetector) {
                            if (imageDetector != null && Detect.this != null && cameraDevice != null) {
                                final Bitmap detect_bitmap= textureView.getBitmap(300,300);
                                detection_results = imageDetector.recognizeImage(detect_bitmap);
                            }
                        }
                    }
                    detectionHandler.post(periodicDetect);
                }
            };

    public void displayFrame()
    {
        if (imageDetector == null || Detect.this == null || cameraDevice == null || detection_results==null) {
            return;
        }
        final Bitmap detect_bitmap= textureView.getBitmap();
        final Bitmap tempBitmap = Bitmap.createBitmap(detect_bitmap.getWidth(), detect_bitmap.getHeight(), Bitmap.Config.RGB_565);
        Canvas tempCanvas = new Canvas(tempBitmap);

        tempCanvas.drawBitmap(detect_bitmap, 0, 0, null);
        Paint paint_border = new Paint();
        paint_border.setStyle(Paint.Style.STROKE);
        paint_border.setColor(Color.RED);
        paint_border.setTextSize(100);

        Paint paint_label = new Paint();
        paint_label.setStyle(Paint.Style.FILL);
        paint_label.setColor(Color.WHITE);
        paint_label.setTypeface(Typeface.DEFAULT_BOLD);
        paint_label.setTextSize(50);

        ArrayList<Pair> circles = new ArrayList<Pair>();
        float xScale=detect_bitmap.getWidth()/300.00f;
        float yScale=detect_bitmap.getHeight()/300.00f;

        boolean draw=true;
        for (final Classifier.Recognition r : detection_results) {
            final RectF location = r.getLocation();
            if (location != null && r.getConfidence() >= 0.1f) {
                draw=true;
                for (Pair circle : circles) {
                    PointF center = (PointF) circle.first;
                    Double radius = Double.parseDouble(circle.second.toString());
                    Double distance = Math.sqrt(Math.pow((center.x - location.centerX()), 2) + Math.pow((center.y - location.centerY()), 2));
                    if (distance < radius) {
                        draw=false;
                    }
                }
                if(draw)
                {
                    PointF center_add = new PointF(location.centerX(), location.centerY());
                    Pair<PointF, Float> circle_add = new Pair<PointF, Float>(center_add, (location.width() / 2.0f));
                    circles.add(circle_add);
                    location.set(location.left*xScale,location.top*yScale,location.right*xScale,location.bottom*yScale);
                    tempCanvas.drawRect(location, paint_border);
                    tempCanvas.drawText(r.getTitle(), location.centerX(), location.centerY(), paint_label);
                }
            }
        }

        final Activity activity = Detect.this;
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    imageView.setImageDrawable(new BitmapDrawable(getResources(), tempBitmap));
                }
            });
        }
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(Detect.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(Detect.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }
    protected void updatePreview() {
        if(null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(Detect.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}
