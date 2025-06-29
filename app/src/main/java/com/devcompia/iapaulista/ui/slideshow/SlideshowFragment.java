package com.devcompia.iapaulista.ui.slideshow;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.devcompia.iapaulista.databinding.FragmentSlideshowBinding;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

public class SlideshowFragment extends Fragment {

    private FragmentSlideshowBinding binding;
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;

    private Executor analysisExecutor = Executors.newSingleThreadExecutor();
    private Bitmap lastFrame = null;
    private static final float MOTION_THRESHOLD = 30000f;

    private LocationManager locationManager;
    private TextView tvGps;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSlideshowBinding.inflate(inflater, container, false);
        View root = binding.getRoot();
        tvGps = binding.textView3; // textView3 no layout

        // Permissões necessárias
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION_PERMISSION);
        }

        return root;
    }

    private void startCamera() {
        // ... câmara existente (sem alterações) ...
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(requireContext());

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                preview.setSurfaceProvider(binding.previewViewCamera.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void analyzeFrame(ImageProxy imageProxy) {
        // ... detecção de movimento existente ...
        Bitmap bmp = toBitmap(imageProxy);
        imageProxy.close();
        if (bmp == null) return;

        if (lastFrame != null) {
            float diff = calcDiff(bmp, lastFrame);
            if (diff > MOTION_THRESHOLD) {
                Log.d("MotionDetector", "Movimento detectado! Diferença: " + diff);
            }
        }
        lastFrame = bmp;
    }

    private Bitmap toBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride(), pixelStride = yPlane.getPixelStride();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * rowStride + x * pixelStride;
                if (index < yBuffer.capacity()) {
                    int luminance = yBuffer.get(index) & 0xFF;
                    int pixel = 0xFF000000 | (luminance << 16) | (luminance << 8) | luminance;
                    bitmap.setPixel(x, y, pixel);
                }
            }
        }
        return bitmap;
    }

    private float calcDiff(Bitmap a, Bitmap b) {
        int w = a.getWidth(), h = a.getHeight();
        long sum = 0;
        for (int y = 0; y < h; y += 20) {
            for (int x = 0; x < w; x += 20) {
                int pa = a.getPixel(x, y), pb = b.getPixel(x, y);
                int dr = ((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF);
                int dg = ((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF);
                int db = (pa & 0xFF) - (pb & 0xFF);
                sum += Math.abs(dr) + Math.abs(dg) + Math.abs(db);
            }
        }
        return sum;
    }

    private void startLocationUpdates() {
        locationManager = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000,    // intervalo mínimo em ms
                    1,       // distância mínima em metros
                    locationListener
            );
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location loc) {
            tvGps.setText(String.format("GPS: %.5f, %.5f", loc.getLatitude(), loc.getLongitude()));
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(@NonNull String provider) { }
        @Override public void onProviderDisabled(@NonNull String provider) { }
    };

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQUEST_CAMERA_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
        if (req == REQUEST_LOCATION_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
        binding = null;
    }
}
