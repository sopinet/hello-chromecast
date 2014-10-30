package com.sopinet.hellochromecast;

import java.io.IOException;
import java.util.ArrayList;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.Cast.ApplicationConnectionResult;
import com.google.android.gms.cast.Cast.MessageReceivedCallback;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

public class MainActivity extends ActionBarActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int REQUEST_CODE = 1;

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;
    private CastDevice mSelectedDevice;
    private GoogleApiClient mApiClient;
    private HelloWorldChannel mHelloWorldChannel;
    private boolean mApplicationStarted;
    private boolean mWaitingForReconnect;
    private String mSessionId;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(
                android.R.color.transparent));

        // Cuando el usuario pulsa el botón, se utiliza el reconocimiento de voz de Android
        // para obtener el texto.
        Button voiceButton = (Button) findViewById(R.id.voiceButton);
        voiceButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoiceRecognitionActivity();
            }
        });

        // Obtiene una instancia MediaRouter. Es necesaria durante el ciclo de envío.
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        // MediaRouter necesita filtrar los dispositivos que pueden iniciar la
        // receiver app asociada con la sender app.
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(getResources()
                        .getString(R.string.app_id))).build();
        mMediaRouterCallback = new MyMediaRouterCallback();
    }

    /**
     * Reconocimiento de voz de Android
     */
    private void startVoiceRecognitionActivity() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.message_to_cast));
        startActivityForResult(intent, REQUEST_CODE);
    }

    /*
    * Maneja la respuesta de reconocimiento de voz
    *
    * @see android.support.v4.app.FragmentActivity#onActivityResult(int, int,
    * android.content.Intent)
    */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            ArrayList<String> matches = data
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches.size() > 0) {
                Log.d(TAG, matches.get(0));
                sendMessage(matches.get(0));
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);

        // Asigna MediaRouteSelector a MediaRouteActionSelector en el menú del ActionBar
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider =
                (MediaRouteActionProvider) MenuItemCompat.getActionProvider(mediaRouteMenuItem);
        mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);

        return true;
    }

    /**
     * Callback para eventos de MediaRouter
     * Cuando el usuario selecciona un dispositivo de la lista mediante button Cast,
     * la app es informada del dispositivo seleccionado.
     */
    private class MyMediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(MediaRouter router, RouteInfo info) {
            mSelectedDevice = CastDevice.getFromBundle(info.getExtras());
            launchReceiver();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, RouteInfo info) {
            teardown();
            mSelectedDevice = null;
        }
    }

    /**
     * Inicia la receiver app
     */
    private void launchReceiver() {
        Cast.Listener mCastListener;
        ConnectionCallbacks mConnectionCallbacks;
        ConnectionFailedListener mConnectionFailedListener;
        try {
            mCastListener = new Cast.Listener() {
                @Override
                public void onApplicationDisconnected(int errorCode) {
                    Log.d(TAG, "Application has stopped");
                    teardown();
                }
            };
            // Connecta a Google Play services
            mConnectionCallbacks = new ConnectionCallbacks();
            mConnectionFailedListener = new ConnectionFailedListener();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(mSelectedDevice, mCastListener);
            mApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mConnectionFailedListener).build();
            mApiClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed launchReceiver", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Dispara el descubrimiento de dispositivos
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // Detiene el descubrimiento de dispositivos
            mMediaRouter.removeCallback(mMediaRouterCallback);
        }
        super.onPause();
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements
            GoogleApiClient.ConnectionCallbacks {

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected");
            if (mApiClient == null) {
                // Está en estado de desconexión.
                return;
            }
            try {
                if (mWaitingForReconnect) {
                    mWaitingForReconnect = false;
                    // Comprueba si la receiver app está en ejecución
                    if ((connectionHint != null) && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
                        Log.d(TAG, "App is no longer running");
                        teardown();
                    } else {
                        // Vuelve a crear el canal personalizado del mensaje
                        try {
                            Cast.CastApi.setMessageReceivedCallbacks(mApiClient,
                                    mHelloWorldChannel.getNamespace(), mHelloWorldChannel);
                        } catch (IOException e) {
                            Log.e(TAG, "Exception while creating channel", e);
                        }
                    }
                } else {
                    // Lanza la receiver app
                    Cast.CastApi.launchApplication(mApiClient,
                            getString(R.string.app_id), false).setResultCallback(
                            new ResultCallback<ApplicationConnectionResult>() {
                                @Override
                                public void onResult(
                                        ApplicationConnectionResult result) {
                                    Status status = result.getStatus();
                                    Log.d(TAG,
                                            "ApplicationConnectionResultCallback.onResult: statusCode"
                                                    + status.getStatusCode());
                                    if (status.isSuccess()) {
                                        ApplicationMetadata applicationMetadata = result
                                                .getApplicationMetadata();
                                        mSessionId = result.getSessionId();
                                        String applicationStatus = result.getApplicationStatus();
                                        boolean wasLaunched = result.getWasLaunched();
                                        Log.d(TAG,
                                                "application name: " + applicationMetadata.getName()
                                                 + ", status: " + applicationStatus
                                                 + ", sessionId: " + mSessionId
                                                 + ", wasLaunched: "+ wasLaunched);
                                        mApplicationStarted = true;
                                        // Crea el canal personalizado del mensaje
                                        mHelloWorldChannel = new HelloWorldChannel();
                                        try {
                                            Cast.CastApi.setMessageReceivedCallbacks(
                                                    mApiClient, mHelloWorldChannel.getNamespace(),
                                                    mHelloWorldChannel);
                                        } catch (IOException e) {
                                            Log.e(TAG,"Exception while creating channel", e);
                                        }
                                        // Establece las instrucciones iniciales en el receptor
                                        sendMessage(getString(R.string.instructions));
                                    } else {
                                        Log.e(TAG, "application could not launch");
                                        teardown();
                                    }
                                }
                            });
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch application", e);
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended");
            mWaitingForReconnect = true;
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionFailedListener implements
            GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e(TAG, "onConnectionFailed ");
            teardown();
        }
    }

    /**
     * Finaliza la conexión con el receptor
     */
    private void teardown() {
        Log.d(TAG, "teardown");
        if (mApiClient != null) {
            if (mApplicationStarted) {
                if (mApiClient.isConnected() || mApiClient.isConnecting()) {
                    try {
                        Cast.CastApi.stopApplication(mApiClient, mSessionId);
                        if (mHelloWorldChannel != null) {
                            Cast.CastApi.removeMessageReceivedCallbacks(mApiClient,
                                    mHelloWorldChannel.getNamespace());
                            mHelloWorldChannel = null;
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Exception while removing channel", e);
                    }
                    mApiClient.disconnect();
                }
                mApplicationStarted = false;
            }
            mApiClient = null;
        }
        mSelectedDevice = null;
        mWaitingForReconnect = false;
        mSessionId = null;
    }

    /**
     * Envia el mensaje al receiver
     *
     * @param message Mensaje
     */
    private void sendMessage(String message) {
        if (mApiClient != null && mHelloWorldChannel != null) {
            try {
                Cast.CastApi.sendMessage(mApiClient,
                        mHelloWorldChannel.getNamespace(), message)
                        .setResultCallback(new ResultCallback<Status>() {
                            @Override
                            public void onResult(Status result) {
                                if (!result.isSuccess()) {
                                    Log.e(TAG, "Sending message failed");
                                }
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Exception while sending message", e);
            }
        } else {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Canal personalizado del mensaje
     */
    class HelloWorldChannel implements MessageReceivedCallback {

        /**
         * @return Obtiene el espacio de nombres
         */
        public String getNamespace() {
            return getString(R.string.namespace);
        }

        /*
        * Recibe el mensaje de la receiver app
        */
        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace,
                                      String message) {
            Log.d(TAG, "onMessageReceived: " + message);
        }
    }
}
