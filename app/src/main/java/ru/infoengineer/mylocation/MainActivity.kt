package ru.infoengineer.mylocation

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class PasswordData(val href: String, val method: String, val templated: Boolean)
data class TokenData(val token: String)

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val yaMapKey: String = BuildConfig.YA_MAP_KEY
        MapKitFactory.setApiKey(yaMapKey)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var location: Location
    private val permissionId = 42
    private var client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapKitFactory.initialize(this)
        setContentView(R.layout.activity_main)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        mapView = findViewById(R.id.map_view)
        if (checkPermissions())
            getLocationUpdates()
        else
            requestPermissions()
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> true
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> true
            else -> false
        }
    }

    private fun checkPermissions(): Boolean {
        return when {
            (ActivityCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) -> true
            else -> false
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), permissionId
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionId) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                getLocationUpdates()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getLocationUpdates() {
        locationRequest = LocationRequest()
        locationRequest.interval = 10000
        locationRequest.fastestInterval = 5000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                location = locationResult.lastLocation!!
                runOnUiThread {
                    val point = Point(location.latitude, location.longitude)
                    mapView.map.move(
                        CameraPosition(point, 16.0f, 0.0f, 0.0f),
                        Animation(Animation.Type.SMOOTH, 2.0f), null
                    )
                    val mapObjects: MapObjectCollection = mapView.map.mapObjects
                    mapObjects.clear()
                    mapObjects.addPlacemark(
                        point,
                        ImageProvider.fromResource(applicationContext, R.drawable.marker)
                    )
                }
            }
        }
        if (!isLocationEnabled()) {
            runOnUiThread {
                val toast = Toast.makeText(applicationContext, getString(R.string.turn_on_location), Toast.LENGTH_LONG)
                toast.setGravity(Gravity.CENTER,0,0)
                toast.show()
            }
        }
    }

    fun clickButton(v: View) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.dialog_title)
        builder.setMessage(R.string.dialog_message)
        builder.setIcon(R.drawable.address)
        builder.setPositiveButton("Да") { _, _ ->
            if (isOnline())
                run()
            else {
                runOnUiThread {
                    val toast = Toast.makeText(applicationContext, getString(R.string.turn_on_internet), Toast.LENGTH_LONG)
                    toast.setGravity(Gravity.CENTER,0,0)
                    toast.show()
                }
            }

        }
        builder.setNegativeButton("Нет") { _, _ ->

        }
        builder.setNeutralButton("Отмена") { _, _ ->

        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun run() {
        val yaSecret: String = BuildConfig.YA_SECRET
        val urlBuilder: HttpUrl.Builder = "https://cloud-api.yandex.net/v1/disk/resources/download".toHttpUrl()
            .newBuilder()
        urlBuilder.addQueryParameter("path", yaSecret)

        val url: String = urlBuilder.build().toString()
        val yaToken: String = BuildConfig.YA_TOKEN
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "OAuth $yaToken")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    val gson = Gson()
                    val pd = gson.fromJson(response.body!!.string(), PasswordData::class.java)
                    run2(pd.href)
                }
            }
        })
    }

    private fun run2(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    run3(response.body!!.string().trim())
                }
            }
        })
    }

    private fun run3(password: String) {
        val credential = Credentials.basic("admin", password)

        val request = Request.Builder()
            .url("https://infoengineer.ru/secret/generate/token")
            .header("Authorization", credential)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    val gson = Gson()
                    val td = gson.fromJson(response.body!!.string(), TokenData::class.java)
                    run4(td.token)
                }
            }
        })
    }

    private fun run4(token: String) {
        val urlBuilder: HttpUrl.Builder = "https://infoengineer.ru/api/location/add".toHttpUrl()
            .newBuilder()
            .addQueryParameter("lat", location.latitude.toString())
            .addQueryParameter("lon", location.longitude.toString())

        val url: String = urlBuilder.build().toString()
        val request = Request.Builder()
            .url(url)
            .header("x-access-tokens", token)
            .post("".toRequestBody())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    println(response.body!!.string())
                    runOnUiThread {
                        val toast = Toast.makeText(applicationContext, getString(R.string.successful), Toast.LENGTH_LONG)
                        toast.setGravity(Gravity.CENTER,0,0)
                        toast.show()
                    }
                }
            }
        })
    }

    override fun onStop() {
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    override fun onStart() {
        mapView.onStart()
        MapKitFactory.getInstance().onStart()
        super.onStart()
    }

    private fun startLocationUpdates() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                null,
            )
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }
}