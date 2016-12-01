package com.soa.ivan.digitalizadorv2;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;


public class CameraActivity extends Activity implements PictureCallback, SurfaceHolder.Callback, SensorEventListener {

    public static final String EXTRA_CAMERA_DATA = "camera_data";

    private static final String KEY_IS_CAPTURING = "is_capturing";

    //manejo camara
    private ImageView mCameraImage;
    private SurfaceView mCameraPreview;
    private SurfaceHolder surfaceHolder;
    int rotation;
    private Camera camera;
    private Button mCaptureImageButton;
    private byte[] mCameraData;
    private boolean mIsCapturing;
    private Bitmap mCameraBitmap;
    private File imageFile;

    //manejo conexion bluetooth
    String address = null;
    private ProgressDialog progress;
    BluetoothAdapter myBluetooth = null;
    BluetoothSocket btSocket = null;
    private boolean isBtConnected = false;
    static final UUID myUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    //manejo para peticiones bluetooth
    final int handlerState = 0;
    private ConnectedThread mConnectedThread;
    Handler bluetoothIn;
    boolean processingImage = false;

    //manejo sensores
    private SensorManager managerProx;
    private Sensor proximidad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(!isBtConnected) {
            Intent newint = getIntent();
            address = newint.getStringExtra(MainActivity.EXTRA_ADDRESS); //recibimos los datos del intent de main activity

            bluetoothIn = new Handler() {
                public void handleMessage(android.os.Message msg) {
                    if(!processingImage) {
                        String readMessage = (String) msg.obj;  //lee mensajes del hilo que escucha al bluetooth
                        processingImage = true;
                        captureImage();
                    }

                }
            };

            new ConnectBT().execute();
        }

        managerProx = (SensorManager) getSystemService(SENSOR_SERVICE);
        proximidad = managerProx.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        setContentView(R.layout.activity_camera);

        mCameraImage = (ImageView) findViewById(R.id.camera_image_view);
        mCameraImage.setVisibility(View.INVISIBLE);

        mCameraPreview = (SurfaceView) findViewById(R.id.preview_view);
        surfaceHolder = mCameraPreview.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mCaptureImageButton = (Button) findViewById(R.id.capture_image_button);
        mCaptureImageButton.setOnClickListener(mCaptureImageButtonClickListener);

        final Button doneButton = (Button) findViewById(R.id.done_button);
        doneButton.setOnClickListener(mDoneButtonClickListener);

        mIsCapturing = true;
    }


    private void msg(String s)
    {
        Toast.makeText(getApplicationContext(),s,Toast.LENGTH_LONG).show();
    }

    private class ConnectBT extends AsyncTask<Void, Void, Void>  // UI thread
    {
        private boolean ConnectSuccess = true; //if it's here, it's almost connected

        @Override
        protected void onPreExecute()
        {
            progress = ProgressDialog.show(CameraActivity.this, "Conectando", "Por favor, espere.");  //show a progress dialog
        }

        @Override
        protected Void doInBackground(Void... devices) //se muestra el progress dialog en la pantalla y mientras se hace esto
        {
            try
            {
                if (btSocket == null || !isBtConnected)
                {
                    myBluetooth = BluetoothAdapter.getDefaultAdapter();//obtener dispositivo bluetooth
                    BluetoothDevice dispositivo = myBluetooth.getRemoteDevice(address);//conectar y verificar disponibilidad
                    btSocket = dispositivo.createInsecureRfcommSocketToServiceRecord(myUUID);//create a RFCOMM (SPP) connection
                    BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                    btSocket.connect();

                    //empzamos hilo que escucha peticiones
                    mConnectedThread = new ConnectedThread(btSocket);
                    mConnectedThread.start();
                }
            }
            catch (IOException e)
            {
                ConnectSuccess = false;
            }
            return null;
        }
        @Override
        protected void onPostExecute(Void result) //despues de terminar doInBackground hace esto
        {
            super.onPostExecute(result);

            if (!ConnectSuccess)
            {
                msg("Conexion fallida. Intente nuevamente.");
                finish();
            }
            else
            {
                msg("Conectado!");
                isBtConnected = true;
            }
            progress.dismiss(); // sacamos el progress dialog
        }

    }

    //para escuchar constantemente request de la placa
    private class ConnectedThread extends Thread {
        BufferedReader reader;

        public ConnectedThread(BluetoothSocket socket) {

            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            } catch (IOException e) {
            }

        }

        public void run() {

            while (true) { //habria que cambiarlo por una variable asi corta cuando la actividad esta pausada
                try {
                    String readMessage = reader.readLine();
                    // enviamos mensaje recibido por bluetooth al handler
                    bluetoothIn.obtainMessage(handlerState, readMessage).sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }
    }

    private boolean openCamera() {
        boolean result = false;
        releaseCamera();
        try {
            camera = Camera.open();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (camera != null) {
            try {
                setUpCamera(camera);
                camera.setErrorCallback(new Camera.ErrorCallback() {

                    @Override
                    public void onError(int error, Camera camera) {

                    }
                });
                camera.setPreviewDisplay(surfaceHolder);
                camera.startPreview();
                result = true;
            } catch (IOException e) {
                e.printStackTrace();
                result = false;
                releaseCamera();
            }
        }
        return result;
    }

    private void setUpCamera(Camera c) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(Camera.CameraInfo.CAMERA_FACING_BACK, info);
        rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degree = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degree = 0;
                break;
            case Surface.ROTATION_90:
                degree = 90;
                break;
            case Surface.ROTATION_180:
                degree = 180;
                break;
            case Surface.ROTATION_270:
                degree = 270;
                break;

            default:
                break;
        }

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            // frontFacing
            rotation = (info.orientation + degree) % 330;
            rotation = (360 - rotation) % 360;
        } else {
            // Back-facing
            rotation = (info.orientation - degree + 360) % 360;
        }
        c.setDisplayOrientation(rotation);
        Camera.Parameters params = c.getParameters();


        List<String> focusModes = params.getSupportedFlashModes();
        if (focusModes != null) {
            if (focusModes
                    .contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFlashMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
        }

        params.setRotation(rotation);
    }

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.setErrorCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            camera = null;
        }
    }


    private OnClickListener mCaptureImageButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            captureImage();
        }
    };

    private OnClickListener mRecaptureImageButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            setupImageCapture();
        }
    };

    private OnClickListener mDoneButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            disconnect();
        }
    };

    private void disconnect()
    {
        if (btSocket!=null) //If the btSocket is busy
        {
            try
            {
                btSocket.close(); //close connection
            }
            catch (IOException e)
            { msg("Error");}
        }
        finish();

    }


    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        savedInstanceState.putBoolean(KEY_IS_CAPTURING, mIsCapturing);
    }



    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        mIsCapturing = savedInstanceState.getBoolean(KEY_IS_CAPTURING, mCameraData == null);
        if (mCameraData == null)
            setupImageCapture();
    }

    @Override
    protected void onResume() {
        super.onResume();

        //TODO deberiamos arrancar el hilo de escucha de peticiones de nuevo
        managerProx.registerListener(this, proximidad, SensorManager.SENSOR_DELAY_UI);

        if (camera == null) {
            try {
                camera = Camera.open();
                camera.setPreviewDisplay(mCameraPreview.getHolder());
                if (mIsCapturing) {
                    camera.startPreview();
                }
            } catch (Exception e) {
                Toast.makeText(CameraActivity.this, "No se pudo abrir la camara.", Toast.LENGTH_LONG)
                        .show();
            }
        }
    }



    @Override
    protected void onPause() {
        super.onPause();

        //TODO deberiamos parar el hilo de escucha de peticiones
        managerProx.unregisterListener(this);

        if (camera != null) {
            camera.release();
            camera = null;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.setPreviewDisplay(holder);
                if (mIsCapturing) {
                    camera.startPreview();
                }
            } catch (IOException e) {
                Toast.makeText(CameraActivity.this, "Vista previa no disponible. Reinicie la conexion..", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void captureImage() {
        camera.takePicture(null, null, this);
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        mCameraData = data;
        new SaveImageTask().execute();
        setupImageCapture();
    }

    private class SaveImageTask extends AsyncTask<Void, Void, Void>
    {
        @Override
        protected void onPreExecute()
        {
            progress = ProgressDialog.show(CameraActivity.this, "Guardando foto", "Por favor. Paciencia!");
        }

        @Override
        protected Void doInBackground(Void... devices)
        {
            java.util.Date date = new java.util.Date();
            String stamp = new Timestamp(date.getTime()).toString();

            try {
                // convertir datos de la camara a un bitmap
                Bitmap loadedImage = null;
                Bitmap rotatedBitmap = null;
                loadedImage = BitmapFactory.decodeByteArray(mCameraData, 0,
                        mCameraData.length);

                // rotamos imagen (probar)
                Matrix rotateMatrix = new Matrix();
                rotateMatrix.postRotate(90);
                rotatedBitmap = Bitmap.createBitmap(loadedImage, 0, 0,
                        loadedImage.getWidth(), loadedImage.getHeight(),
                        rotateMatrix, false);

                rotatedBitmap = ChangeBitmapMutability.convertToMutable(rotatedBitmap);

                //convertimos la imagen a positivo (o negativo si saque una foto en positivo)
                for (int x = 0; x < rotatedBitmap.getWidth(); x++) {
                    for (int y = 0; y < rotatedBitmap.getHeight(); y++) {
                        int pixel = rotatedBitmap.getPixel(x, y);
                        int alpha = Color.alpha(pixel);
                        int rojo = 255 - Color.red(pixel);
                        int azul = 255 - Color.blue(pixel);
                        int verde = 255 - Color.green(pixel);
                        rotatedBitmap.setPixel(x, y, Color.argb(alpha, rojo, verde, azul));
                    }
                }

                //obtenemos info del almacenamiento y de la carpeta donde se guarda la foto
                String state = Environment.getExternalStorageState();
                File folder = null;
                if (state.contains(Environment.MEDIA_MOUNTED)) {
                    folder = new File(Environment
                            .getExternalStorageDirectory() + "/Digitalizador");
                } else {
                    folder = new File(Environment
                            .getExternalStorageDirectory() + "/Digitalizador");
                }

                boolean success = true;
                if (!folder.exists()) {
                    success = folder.mkdirs();
                }
                if (success) {

                    imageFile = new File(folder.getAbsolutePath()
                            + File.separator
                            + stamp
                            + "Imagen.jpg");

                    imageFile.createNewFile(); //crea archivo pero no tiene nada
                } else {
                    Toast.makeText(getBaseContext(), "Imagen no guardada",
                            Toast.LENGTH_SHORT).show();
                    return null;
                }

                ByteArrayOutputStream ostream = new ByteArrayOutputStream();

                // guardamos imagen en la galeria en el archivo imageFile antes creado
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, ostream);

                FileOutputStream fout = new FileOutputStream(imageFile);
                fout.write(ostream.toByteArray());
                fout.close();
                ContentValues values = new ContentValues();

                values.put(MediaStore.Images.Media.DATE_TAKEN,
                        System.currentTimeMillis());
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.DATA,
                        imageFile.getAbsolutePath());

                CameraActivity.this.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                processingImage = false;


            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
        @Override
        protected void onPostExecute(Void result)
        {
            super.onPostExecute(result);
            progress.dismiss();
        }

    }


    private void setupImageCapture() {
        mCameraImage.setVisibility(View.INVISIBLE);
        mCameraPreview.setVisibility(View.VISIBLE);
        camera.startPreview();
        mCaptureImageButton.setText(R.string.capture_image);
        mCaptureImageButton.setOnClickListener(mCaptureImageButtonClickListener);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType()== Sensor.TYPE_PROXIMITY && event.values[0] >= -4.0 && event.values[0]<= 4.0) {
            Intent dialogIntent = new Intent(CameraActivity.this, AlertDialogActivity.class);
            startActivity(dialogIntent);

            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(2000);
        }

    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }


}
