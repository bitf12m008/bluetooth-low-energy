package com.precor.btfitness

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

class HealthConnectManager(private val context: Context) {
    val healthConnectClient: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        healthConnectClient.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    fun requestPermissionsActivityContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    fun isHealthConnectAvailable(): Boolean {
        return try {
            HealthConnectClient.getOrCreate(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun promptInstallHealthConnect() {
        Toast.makeText(context, "Health Connect is not installed. Redirecting to Play Store...", Toast.LENGTH_LONG).show()
        try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).apply {
                setPackage("com.android.vending") // Forces Play Store
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
}

val PERMISSIONS = setOf(
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getWritePermission(DistanceRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getWritePermission(ExerciseSessionRecord::class)
)
