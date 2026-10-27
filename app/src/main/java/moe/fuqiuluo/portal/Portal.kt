package moe.fuqiuluo.portal

import android.app.Application
import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

class Portal: Application() {

    override fun onCreate() {
        super.onCreate()

        appContext = applicationContext
    }

    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"

        lateinit var appContext: Context
        @Volatile private var onlineMapInitialized = false

        /** Call only after the user has explicitly chosen to enable the online map. */
        fun initializeOnlineMap(): Boolean = synchronized(this) {
            if (onlineMapInitialized) return@synchronized true
            runCatching {
                SDKInitializer.setAgreePrivacy(appContext, true)
                LocationClient.setAgreePrivacy(true)
                SDKInitializer.initialize(appContext)
                SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)
                onlineMapInitialized = true
            }.isSuccess
        }

        fun isOnlineMapInitialized(): Boolean = onlineMapInitialized
        //val DEFAULT_COORD_TYPE = CoordType.BD09LL
        //const val DEFAULT_COORD_STR = "bd09ll"
    }
}
