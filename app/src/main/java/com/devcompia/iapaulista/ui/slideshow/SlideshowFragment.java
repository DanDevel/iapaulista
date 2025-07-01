package com.devcompia.iapaulista.ui.slideshow;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.YuvImage;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.devcompia.iapaulista.databinding.FragmentSlideshowBinding;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import androidx.camera.core.ImageCaptureException;



public class SlideshowFragment extends Fragment {

    private FragmentSlideshowBinding binding;
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final float MOTION_THRESHOLD = 10000f;
    private long lastUploadTime = 0; // controla intervalo mínimo de envio


    private Executor analysisExecutor = Executors.newSingleThreadExecutor();
    private Bitmap lastFrame = null;

    private LocationManager locationManager;
    private TextView tvGps, tvAddress;
    private Location currentLocation;
    private Address currentAddress;

    private static final String ENDPOINT = "https://sa-east-1.aws.data.mongodb-api.com/app/data-sdgai/endpoint/data/v1";
    private static final String API_KEY = "xxxxxxxxxxxxx"; // Adicione sua chave
    private ImageCapture imageCapture;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSlideshowBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Impede o bloqueio da tela
        requireActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvGps = binding.textView3;
        tvAddress = binding.textView5;

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        }

        return root;
    }


    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cp = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector sel = CameraSelector.DEFAULT_BACK_CAMERA;
                preview.setSurfaceProvider(binding.previewViewCamera.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(requireActivity().getWindowManager().getDefaultDisplay().getRotation())
                        .build();

                cp.unbindAll();
                cp.bindToLifecycle(this, sel, preview, analysis, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }


    private void analyzeFrame(ImageProxy ip) {
        Bitmap bmp = toBitmap(ip);
        ip.close();
        if (bmp == null) return;

        if (lastFrame != null) {
            float diff = calcDiff(bmp, lastFrame);
            if (diff > MOTION_THRESHOLD) {
                Log.d("MotionDetector", "Movimento detectado! diff=" + diff);
                handleMotion(bmp);
            }
        }
        lastFrame = bmp;
    }

    private void handleMotion(Bitmap bmpFromAnalysis) {
        // Verifica se o switch2 está ligado
        if (binding.switch2 != null && !binding.switch2.isChecked()) {
            Log.d("MotionHandler", "Switch2 desligado, não enviando imagem.");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastUploadTime < 30000) {
            Log.d("MotionHandler", "Ignorado: menos de 1 minuto desde o último envio");
            return;
        }
        lastUploadTime = now;

        if (imageCapture == null) {
            Log.e("MotionHandler", "ImageCapture não inicializado");
            return;
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            ImageCapture.OutputFileOptions outputOptions =
                    new ImageCapture.OutputFileOptions.Builder(outputStream).build();

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(requireContext()),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            byte[] jpegData = outputStream.toByteArray();
                            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                            if (bitmap != null) {
                                saveAndSend(bitmap, now);
                            } else {
                                Log.e("MotionHandler", "Falha ao decodificar imagem capturada.");
                            }
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            Log.e("MotionHandler", "Erro ao salvar imagem: " + exception.getMessage(), exception);
                        }
                    });
        }, 800);
    }






    private void sendToMongo(JSONObject doc) throws IOException, JSONException {
        URL url = new URL("https://sa-east-1.aws.data.mongodb-api.com/app/data-sdgai/endpoint/data/v1/action/insertOne");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Access-Control-Request-Headers", "*");
        conn.setRequestProperty("api-key", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        conn.setDoOutput(true);

        JSONObject payload = new JSONObject();
        payload.put("collection", "iapaulistacoll");
        payload.put("database", "iapaulista");
        payload.put("dataSource", "ClusterData1");
        payload.put("document", doc);


        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes());
        }

        int code = conn.getResponseCode();
        Log.d("MongoUpload", "HTTP response " + code);
        conn.disconnect();
    }


    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap toBitmap(ImageProxy ip) {
        Image img = ip.getImage();
        if (img == null) return null;
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer buf = pl.getBuffer();
        int row = pl.getRowStride(), pix = pl.getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int idx = y * row + x * pix;
            if (idx < buf.capacity()) {
                int lum = buf.get(idx) & 0xFF;
                bmp.setPixel(x, y, 0xFF000000 | lum << 16 | lum << 8 | lum);
            }
        }
        return bmp;
    }

    private float calcDiff(Bitmap a, Bitmap b) {
        int w = a.getWidth(), h = a.getHeight();
        long sum = 0;
        for (int y = 0; y < h; y += 20)
            for (int x = 0; x < w; x += 20) {
                int pa = a.getPixel(x, y), pb = b.getPixel(x, y);
                sum += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
            }
        return sum;
    }

    private void startLocationUpdates() {
        locationManager = requireContext().getSystemService(LocationManager.class);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, locationListener);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location loc) {
            currentLocation = loc;
            tvGps.setText(String.format(Locale.getDefault(), "GPS: %.5f, %.5f", loc.getLatitude(), loc.getLongitude()));
            updateAddress(loc);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(@NonNull String p) {}
        @Override public void onProviderDisabled(@NonNull String p) {}
    };

    private void updateAddress(Location loc) {
        Geocoder geocoder = new Geocoder(requireContext(), Locale.getDefault());
        try {
            List<Address> l = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
            if (l != null && !l.isEmpty()) {
                currentAddress = l.get(0);
                tvAddress.setText(currentAddress.getAddressLine(0));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQUEST_CAMERA_PERMISSION && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        if (req == REQUEST_LOCATION_PERMISSION && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startLocationUpdates();
    }


    private void saveAndSend(Bitmap bmp, long timestamp) {
        new Thread(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                String img64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                JSONObject doc = new JSONObject();
                doc.put("timestamp", timestamp);
                if (currentLocation != null) {
                    doc.put("latitude", currentLocation.getLatitude());
                    doc.put("longitude", currentLocation.getLongitude());
                }
                if (currentAddress != null) {
                    doc.put("address", currentAddress.getAddressLine(0));
                    doc.put("country", currentAddress.getCountryName());
                    doc.put("state", currentAddress.getAdminArea());
                    doc.put("city", currentAddress.getLocality());
                    doc.put("district", currentAddress.getSubLocality());
                    doc.put("street", currentAddress.getThoroughfare());
                    doc.put("number", currentAddress.getFeatureName());
                    doc.put("userid", "iapaulista1");
                }
                doc.put("image64", img64);

                sendToMongo(doc);
            } catch (Exception e) {
                Log.e("saveAndSend", e.getMessage(), e);
            }
        }).start();
    }

    @OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private Bitmap toColorBitmap(ImageProxy image) {

        Image img = image.getImage();
        if (img == null) return null;

        YuvImage yuvImage = new YuvImage(getNV21(img), android.graphics.ImageFormat.NV21, img.getWidth(), img.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, img.getWidth(), img.getHeight()), 90, out);
        byte[] imageBytes = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private byte[] getNV21(Image image) {
        ByteBuffer y = image.getPlanes()[0].getBuffer();
        ByteBuffer u = image.getPlanes()[1].getBuffer();
        ByteBuffer v = image.getPlanes()[2].getBuffer();

        int ySize = y.remaining();
        int uSize = u.remaining();
        int vSize = v.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        y.get(nv21, 0, ySize);
        v.get(nv21, ySize, vSize);
        u.get(nv21, ySize + vSize, uSize);
        return nv21;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        binding = null;
    }
}
