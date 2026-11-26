package com.zero7goat.taz.core.storage

import android.media.ExifInterface
import android.os.Build
import java.io.File

/**
 * Removes where a photo was taken (GPS data in its EXIF metadata) when it is added to the vault,
 * so a shared copy can't give that away. The picture itself is untouched. Works for JPEG, and
 * for PNG and WebP from Android 11. Other formats, or a photo that can't be rewritten, are kept
 * exactly as they were.
 */
object PhotoPrivacy {

    private val gpsTags = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_VERSION_ID
    )

    fun canStrip(mimeType: String): Boolean = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> true
        "image/png", "image/webp" -> Build.VERSION.SDK_INT >= 30
        else -> false
    }

    /** Returns true if location data was found and removed. Never throws. */
    fun stripLocation(file: File): Boolean =
        try {
            val exif = ExifInterface(file.absolutePath)
            if (gpsTags.none { exif.getAttribute(it) != null }) {
                false
            } else {
                gpsTags.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
                true
            }
        } catch (e: Exception) {
            false
        }
}
