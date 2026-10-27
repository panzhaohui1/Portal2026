package moe.fuqiuluo.portal.ui.home

import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.MainActivity
import moe.fuqiuluo.portal.Portal
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.bdmap.locateMe
import moe.fuqiuluo.portal.bdmap.setMapConfig
import moe.fuqiuluo.portal.databinding.FragmentSelectLocationBinding
import moe.fuqiuluo.portal.ext.CoordConverter
import moe.fuqiuluo.portal.ext.CoordSystem
import moe.fuqiuluo.portal.ext.CoordinateText
import moe.fuqiuluo.portal.ext.gcj02
import moe.fuqiuluo.portal.ext.manualCoordSystem
import moe.fuqiuluo.portal.ext.mapType
import moe.fuqiuluo.portal.ext.rawHistoricalLocations
import moe.fuqiuluo.portal.ext.selectLocation
import moe.fuqiuluo.portal.ext.wgs84
import moe.fuqiuluo.portal.ui.mock.HistoricalLocation
import moe.fuqiuluo.portal.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.portal.ui.viewmodel.MockServiceViewModel
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SelectLocationFragment : Fragment() {
    private var _binding: FragmentSelectLocationBinding? = null
    private val binding get() = _binding!!

    private var mapView: MapView? = null
    private var mLocationClient: LocationClient? = null
    private var mapReady = false
    private var mapInitializationAttempted = false
    private var onlineMapFailed = false
    private var useNextLocationAsSelection = false
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelectLocationBinding.inflate(inflater, container, false)

        val savedLocation = requireContext().selectLocation
        baiduMapViewModel.markedLoc = savedLocation?.let { it.lat to it.lon }
        baiduMapViewModel.markName = savedLocation?.address
        baiduMapViewModel.showDetailView = false

        with(baiduMapViewModel) {
            isExists = false
            onSelectionChanged = { updateSelectionSummary() }
        }

        binding.bmapView.contentDescription = getString(R.string.v2_map_description)
        binding.btnEnableOnlineMap.setOnClickListener {
            showOnlineMapConsentDialog()
        }
        renderOnlineMapButton()

        binding.btnMapType.setOnClickListener {
            if (!mapReady) {
                showToast(getString(R.string.v2_map_unavailable_manual_hint))
                return@setOnClickListener
            }

            runCatching {
                val map = mapView?.map ?: error("在线地图尚未创建")
                map.mapType = if (map.mapType == BaiduMap.MAP_TYPE_NORMAL) {
                    BaiduMap.MAP_TYPE_SATELLITE
                } else {
                    BaiduMap.MAP_TYPE_NORMAL
                }
                context?.mapType = map.mapType
            }.onFailure {
                mapReady = false
                onlineMapFailed = true
                baiduMapViewModel.isExists = false
                runCatching { mapView?.onDestroy() }
                binding.bmapView.removeAllViews()
                mapView = null
                renderOnlineMapButton()
                updateSelectionSummary()
                showToast(getString(R.string.v2_map_unavailable_manual_hint))
            }
        }

        binding.fabMyLocation.setOnClickListener {
            requestCurrentLocation()
        }

        binding.btnManualCoordinates.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabConfirm.setOnClickListener {
            if (baiduMapViewModel.markedLoc == null) {
                binding.selectionStatus.text = getString(R.string.v2_select_location_error)
                showToast(getString(R.string.v2_select_location_error))
            } else {
                showAddLocationDialog()
            }
        }

        updateSelectionSummary()

        return binding.root
    }

    private fun configureMap() {
        val mapView = mapView ?: error("在线地图尚未创建")
        with(baiduMapViewModel) {
            baiduMap = mapView.map
        }

        with(mapView) {
            showZoomControls(false)
            showScaleControl(true)
            logoPosition = LogoPosition.logoPostionRightTop
        }

        with(mapView.map) {
            setMapStatus(MapStatusUpdateFactory.zoomTo(19f))
            mapType = context?.mapType ?: BaiduMap.MAP_TYPE_NORMAL
            compassPosition = Point(50, 50)
            setCompassEnable(true)
            uiSettings.isCompassEnabled = true
            uiSettings.isOverlookingGesturesEnabled = true
            // LocationClient is deliberately disabled until the user taps the location button.
            isMyLocationEnabled = false

            setMapConfig(
                baiduMapViewModel.perspectiveState,
                R.drawable.icon_my_location
            )

            setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng) {
                    markLocation(loc, showDetail = false)
                }

                override fun onMapPoiClick(poi: MapPoi) = Unit
            })

            setOnMapLongClickListener { loc ->
                if (loc != null) {
                    markLocation(loc, showDetail = true)
                }
            }
        }
    }

    private fun requestCurrentLocation() {
        if (!Portal.isOnlineMapInitialized()) {
            showToast(getString(R.string.v2_online_map_disabled_manual_hint))
            return
        }
        useNextLocationAsSelection = true
        val client = ensureLocationClient()
        if (client == null) {
            useNextLocationAsSelection = false
            showToast(getString(R.string.v2_current_location_unavailable))
            return
        }

        val locationRequestStarted = runCatching {
            val onlineMap = mapView?.map
            if (mapReady && onlineMap != null) {
                onlineMap.isMyLocationEnabled = true
                onlineMap.locateMe()
            }
            if (!client.isStarted) {
                client.start()
            }
        }.isSuccess

        if (!locationRequestStarted) {
            useNextLocationAsSelection = false
            showToast(getString(R.string.v2_current_location_unavailable))
            return
        }

        showToast(getString(R.string.v2_requesting_current_location))
    }

    private fun ensureLocationClient(): LocationClient? {
        mLocationClient?.let { return it }

        return runCatching {
            val client = LocationClient(requireContext())
            val option = LocationClientOption().apply {
                isOpenGps = true
                enableSimulateGps = false
                setIsNeedAddress(true)
                setNeedDeviceDirect(true)
                isLocationNotify = true
                setIgnoreKillProcess(true)
                setIsNeedLocationDescribe(false)
                setIsNeedLocationPoiList(false)
                isOpenGnss = true
                setIsNeedAltitude(false)
                locationMode = LocationClientOption.LocationMode.Hight_Accuracy
                setCoorType(Portal.DEFAULT_COORD_STR)
                // This is a user-triggered one-shot request, not a background location stream.
                setScanSpan(0)
            }
            client.locOption = option
            client.registerLocationListener(object : BDAbstractLocationListener() {
                override fun onReceiveLocation(loc: BDLocation?) {
                    if (loc == null) return

                    val locData = MyLocationData.Builder()
                        .accuracy(loc.radius)
                        .direction(loc.direction)
                        .latitude(loc.latitude)
                        .longitude(loc.longitude)
                        .build()
                    val wgsLocation = loc.wgs84

                    lifecycleScope.launch(Dispatchers.Main) {
                        if (_binding == null) return@launch

                        if (loc.city != null) {
                            MainActivity.mCityString = loc.city
                        }
                        baiduMapViewModel.currentLocation = wgsLocation
                        if (mapReady) {
                            runCatching {
                                baiduMapViewModel.baiduMap.setMyLocationData(locData)
                            }
                        }

                        if (useNextLocationAsSelection) {
                            useNextLocationAsSelection = false
                            baiduMapViewModel.markedLoc = wgsLocation
                            baiduMapViewModel.markName = null
                            updateSelectionSummary()
                            if (mapReady) {
                                runCatching { markMap(moveEyes = true) }
                            }
                        }

                        mLocationClient?.stop()
                        if (mapReady) {
                            runCatching { baiduMapViewModel.baiduMap.isMyLocationEnabled = false }
                        }
                    }
                }
            })
            mLocationClient = client
            baiduMapViewModel.mLocationClient = client
            client
        }.getOrNull()
    }

    private fun showInputCoordinatesDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_coordinates, null)
        val pasteEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextPasteCoordinates)
        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)
        val coordSystemGroup = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggleCoordSystem)
        val previewText = dialogView.findViewById<TextView>(R.id.textCoordinatePreview)

        coordSystemGroup.check(
            when (requireContext().manualCoordSystem) {
                CoordSystem.WGS84 -> R.id.btnCoordWgs84
                CoordSystem.GCJ02 -> R.id.btnCoordGcj02
                CoordSystem.BD09 -> R.id.btnCoordBd09
            }
        )

        // The stored selection is WGS84, so prefill only when that system is chosen.
        baiduMapViewModel.markedLoc?.let {
            if (requireContext().manualCoordSystem == CoordSystem.WGS84) {
                latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
                longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
            }
        }

        fun selectedSystem(): CoordSystem = when (coordSystemGroup.checkedButtonId) {
            R.id.btnCoordGcj02 -> CoordSystem.GCJ02
            R.id.btnCoordBd09 -> CoordSystem.BD09
            else -> CoordSystem.WGS84
        }

        fun parsedWgs84(showErrors: Boolean): Pair<Double, Double>? {
            val latitude = latitudeEditText.text?.toString()?.trim()?.toDoubleOrNull()
            val longitude = longitudeEditText.text?.toString()?.trim()?.toDoubleOrNull()
            val latitudeValid = latitude?.let { it.isFinite() && it in -90.0..90.0 } == true
            val longitudeValid = longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
            if (showErrors) {
                latitudeEditText.error = if (latitudeValid) null else getString(R.string.v2_latitude_invalid)
                longitudeEditText.error = if (longitudeValid) null else getString(R.string.v2_longitude_invalid)
            }
            if (!latitudeValid || !longitudeValid) return null
            return CoordConverter.toWgs84(latitude!!, longitude!!, selectedSystem())
        }

        fun updatePreview() {
            val hasInput = !latitudeEditText.text.isNullOrBlank() || !longitudeEditText.text.isNullOrBlank()
            val wgs = parsedWgs84(showErrors = hasInput)
            previewText.text = if (wgs == null) {
                getString(R.string.v2_coordinate_preview_empty)
            } else {
                getString(R.string.v2_coordinate_preview, wgs.first, wgs.second)
            }
        }

        pasteEditText.addTextChangedListener { text ->
            if (text.isNullOrBlank()) return@addTextChangedListener
            val parsed = CoordinateText.parse(text.toString())
            if (parsed == null) {
                pasteEditText.error = getString(R.string.v2_paste_coordinates_invalid)
            } else {
                pasteEditText.error = null
                latitudeEditText.setText(BigDecimal.valueOf(parsed.first).toPlainString())
                longitudeEditText.setText(BigDecimal.valueOf(parsed.second).toPlainString())
            }
        }
        latitudeEditText.addTextChangedListener { updatePreview() }
        longitudeEditText.addTextChangedListener { updatePreview() }
        coordSystemGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updatePreview()
        }
        updatePreview()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.v2_manual_coordinates)
            .setView(dialogView)
            .setPositiveButton(R.string.v2_confirm, null)
            .setNegativeButton(R.string.v2_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val wgs = parsedWgs84(showErrors = true) ?: return@setOnClickListener
                requireContext().manualCoordSystem = selectedSystem()

                useNextLocationAsSelection = false
                baiduMapViewModel.markedLoc = wgs
                baiduMapViewModel.markName = null
                updateSelectionSummary()
                if (mapReady) {
                    runCatching { markMap(moveEyes = true) }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun markLocation(loc: LatLng, showDetail: Boolean) {
        useNextLocationAsSelection = false
        baiduMapViewModel.markedLoc = loc.wgs84
        baiduMapViewModel.markName = null
        baiduMapViewModel.showDetailView = showDetail
        updateSelectionSummary()

        lifecycleScope.launch {
            baiduMapViewModel.mGeoCoder?.reverseGeoCode(
                ReverseGeoCodeOption().location(loc)
            )
        }
        lifecycleScope.launch {
            markMap()
        }
    }

    private fun updateSelectionSummary() {
        if (_binding == null) return

        val location = baiduMapViewModel.markedLoc
        binding.mapPlaceholder.visibility = if (mapReady) View.GONE else View.VISIBLE
        binding.mapPlaceholder.text = getString(
            if (onlineMapFailed) R.string.v2_map_placeholder_failed else R.string.v2_map_placeholder
        )
        binding.btnMapType.visibility = if (mapReady) View.VISIBLE else View.GONE
        binding.fabMyLocation.visibility = if (mapReady) View.VISIBLE else View.GONE
        binding.fabConfirm.isEnabled = location != null
        binding.fabConfirm.alpha = if (location != null) 1f else 0.5f
        if (location == null) {
            binding.selectionStatus.text = getString(R.string.v2_selected_location_none)
            binding.selectionCoordinates.text = getString(R.string.v2_no_coordinates)
            binding.selectionAddress.visibility = if (mapInitializationAttempted && !mapReady) {
                View.VISIBLE
            } else {
                View.GONE
            }
            binding.selectionAddress.text = getString(
                if (mapInitializationAttempted && !mapReady) {
                    R.string.v2_map_unavailable_manual_hint
                } else {
                    R.string.v2_selected_location_address_pending
                }
            )
            return
        }

        binding.selectionStatus.text = getString(R.string.v2_selected_location_ready)
        binding.selectionCoordinates.text = getString(
            R.string.v2_coordinates_format,
            location.first,
            location.second
        )
        val address = baiduMapViewModel.markName?.takeIf { it.isNotBlank() }
        binding.selectionAddress.visibility = if (address == null) View.GONE else View.VISIBLE
        binding.selectionAddress.text = address.orEmpty()
    }

    private fun showAddLocationDialog() {
        val markedLocation = baiduMapViewModel.markedLoc ?: run {
            showToast(getString(R.string.v2_select_location_error))
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        dialogView.findViewById<TextView>(R.id.tvLocationCoordinates).text = getString(
            R.string.v2_coordinates_format,
            markedLocation.first,
            markedLocation.second
        )

        editName.setText(
            getString(
                R.string.v2_default_marked_name,
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            )
        )
        editName.selectAll()
        editAddress.setText(baiduMapViewModel.markName.orEmpty())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton(R.string.v2_save_location, null)
            .setNegativeButton(R.string.v2_cancel, null)
            .create()
        dialog.setOnShowListener {
            editName.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enteredName = editName.text?.toString()?.trim()
                if (enteredName.isNullOrBlank()) {
                    editName.error = getString(R.string.v2_name_required)
                    return@setOnClickListener
                }
                val enteredAddress = editAddress.text?.toString()?.trim().orEmpty()
                val (newLatitude, newLongitude) = markedLocation

                val locations = requireContext().rawHistoricalLocations.toMutableSet()
                var savedName: String = enteredName
                var duplicateIndex = 0
                while (!locations.addLocation(
                        savedName,
                        enteredAddress,
                        newLatitude,
                        newLongitude
                    )
                ) {
                    savedName = "$enteredName(${++duplicateIndex})"
                }
                requireContext().rawHistoricalLocations = locations

                val saved = HistoricalLocation(
                    savedName,
                    enteredAddress,
                    newLatitude,
                    newLongitude
                )
                requireContext().selectLocation = saved
                mockServiceViewModel.selectedLocation = saved
                showToast(getString(R.string.v2_location_saved))
                dialog.dismiss()

                val navController = findNavController()
                if (!navController.popBackStack(R.id.nav_mock, false)) {
                    navController.navigate(R.id.nav_mock)
                }
            }
        }
        dialog.show()
    }

    private fun MutableSet<String>.addLocation(
        name: String,
        address: String,
        latitude: Double,
        longitude: Double
    ): Boolean {
        if (any {
                runCatching { HistoricalLocation.fromString(it).name == name }
                    .getOrDefault(false)
            }
        ) {
            return false
        }

        add(HistoricalLocation(name, address, latitude, longitude).toString())
        return true
    }

    private fun markMap(moveEyes: Boolean = false) {
        if (!mapReady || !baiduMapViewModel.isExists) return
        val markedLocation = baiduMapViewModel.markedLoc ?: return
        val marker = MarkerOptions()
            .position(markedLocation.gcj02)
            .apply {
                baiduMapViewModel.mMapIndicator?.let { icon(it) }
            }

        baiduMapViewModel.baiduMap.clear()
        baiduMapViewModel.baiduMap.addOverlay(marker)
        if (moveEyes) {
            baiduMapViewModel.baiduMap.setMapStatus(
                MapStatusUpdateFactory.newLatLng(markedLocation.gcj02)
            )
        }
    }

    private fun showOnlineMapConsentDialog() {
        if (mapReady) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.v2_online_map_consent_title)
            .setMessage(R.string.v2_online_map_consent_message)
            .setPositiveButton(R.string.v2_online_map_consent_positive) { _, _ ->
                enableOnlineMap()
            }
            .setNegativeButton(R.string.v2_online_map_consent_negative, null)
            .show()
    }

    private fun renderOnlineMapButton() {
        if (_binding == null) return
        binding.btnEnableOnlineMap.visibility = if (mapReady) View.GONE else View.VISIBLE
        binding.btnEnableOnlineMap.isEnabled = !mapReady
        binding.btnEnableOnlineMap.text = getString(
            when {
                mapReady -> R.string.v2_online_map_enabled
                onlineMapFailed -> R.string.v2_retry_online_map
                else -> R.string.v2_enable_online_map
            }
        )
        binding.btnEnableOnlineMap.contentDescription = getString(
            if (onlineMapFailed) R.string.v2_retry_online_map_description
            else R.string.v2_enable_online_map_description
        )
    }

    private fun enableOnlineMap(savedInstanceState: Bundle? = null) {
        if (_binding == null || mapReady) return
        mapInitializationAttempted = true
        onlineMapFailed = false
        val initialized = runCatching {
            check(Portal.initializeOnlineMap())
            (activity as? MainActivity)?.initializeOnlineMapTools()
            val createdMap = MapView(requireContext())
            mapView = createdMap
            binding.bmapView.addView(
                createdMap,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            if (savedInstanceState != null) createdMap.onCreate(requireContext(), savedInstanceState)
            configureMap()
            createdMap.onResume()
        }.isSuccess

        mapReady = initialized
        onlineMapFailed = !initialized
        baiduMapViewModel.isExists = initialized
        if (initialized) {
            if (baiduMapViewModel.markedLoc != null) runCatching { markMap() }
        } else {
            runCatching { mapView?.onDestroy() }
            binding.bmapView.removeAllViews()
            mapView = null
            showToast(getString(R.string.v2_online_map_unavailable_manual_hint))
        }
        renderOnlineMapButton()
        updateSelectionSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Portal.isOnlineMapInitialized()) enableOnlineMap(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateSelectionSummary()
            if (mapReady) {
                runCatching { mapView?.onResume() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mLocationClient?.let {
            if (it.isStarted) {
                it.stop()
            }
        }
        if (_binding != null) {
            if (mapReady) {
                runCatching { mapView?.onPause() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            if (mapReady) {
                runCatching { mapView?.onSaveInstanceState(outState) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        baiduMapViewModel.isExists = false
        mLocationClient?.let {
            if (it.isStarted) {
                it.stop()
            }
        }
        if (_binding != null && mapReady) {
            runCatching { mapView?.map?.isMyLocationEnabled = false }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mLocationClient?.let {
            if (it.isStarted) {
                it.stop()
            }
        }
        mLocationClient = null
        useNextLocationAsSelection = false
        baiduMapViewModel.isExists = false
        baiduMapViewModel.onSelectionChanged = null
        runCatching { mapView?.onDestroy() }
        mapView = null
        mapInitializationAttempted = false
        onlineMapFailed = false
        mapReady = false
        _binding = null
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
